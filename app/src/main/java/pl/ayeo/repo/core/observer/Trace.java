package pl.ayeo.repo.core.observer;

import com.daml.ledger.javaapi.data.ArchivedEvent;
import com.daml.ledger.javaapi.data.CreatedEvent;
import com.daml.ledger.javaapi.data.DamlRecord;
import com.daml.ledger.javaapi.data.Event;
import com.daml.ledger.javaapi.data.ExercisedEvent;
import com.daml.ledger.javaapi.data.Transaction;
import com.daml.ledger.javaapi.data.TransactionShape;
import com.daml.ledger.javaapi.data.Value;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import pl.ayeo.repo.actors.ActorRole;
import pl.ayeo.repo.ledger.LedgerClient;
import pl.ayeo.repo.ledger.LedgerConfig;
import pl.ayeo.repo.ledger.Refusals;
import pl.ayeo.repo.core.render.Amounts;

public final class Trace {

  public record Moment(
      String kind,
      String template,
      String detail,
      DamlRecord payload,
      String actor,
      String watchedBy,
      String contractId,
      int nodeId,
      int lastDescendant) {}

  public record Step(
      String updateId, String at, Map<String, List<Moment>> byNode, String[] actor, int[] root) {}

  public static int depth(Moment moment, List<Moment> siblings) {
    return (int)
        siblings.stream()
            .filter(other -> other.nodeId() < moment.nodeId())
            .filter(other -> other.lastDescendant() >= moment.nodeId())
            .count();
  }

  // Milliseconds, so two events within one second still sort in order.
  private static final DateTimeFormatter CLOCK =
      DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

  private record Holding(String owner, String unit, boolean cash, BigDecimal amount, String register) {}

  public record Run(List<String> nodes, List<Step> steps, Map<String, List<Net>> net) {}

  public record Net(String unit, boolean cash, BigDecimal amount) {}

  public static Run read(LedgerConfig config) throws Exception {
    List<LedgerConfig.Role> roles =
        Arrays.stream(ActorRole.values()).map(role -> config.role(role.key())).toList();

    // Ordered by the time the synchronizer stamped, which every node agrees on;
    // offsets are per-participant and would not line up.
    String ourIcsd = config.role(ActorRole.ICSD.key()).displayName();
    String ourCentralBank = config.role(ActorRole.CENTRAL_BANK.key()).displayName();

    Map<String, Step> steps = new TreeMap<>();
    Map<String, Map<String, BigDecimal>> netted = new LinkedHashMap<>();
    for (LedgerConfig.Role role : roles) {
      try (LedgerClient node = LedgerClient.connect(role)) {
        // An archival carries no payload, so remember what each contract held.
        Map<String, Holding> known = new LinkedHashMap<>();
        for (Transaction transaction :
            node.transactions(role.party(), 0L, node.ledgerEnd(), TransactionShape.LEDGER_EFFECTS)) {
          String key = transaction.getEffectiveAt() + "|" + transaction.getUpdateId();
          Step step =
              steps.computeIfAbsent(
                  key,
                  ignored ->
                      new Step(
                          transaction.getUpdateId(),
                          CLOCK.format(transaction.getEffectiveAt()),
                          new LinkedHashMap<>(),
                          new String[] {""},
                          new int[] {Integer.MAX_VALUE}));
          List<Moment> seen = new ArrayList<>();
          for (Change change : balanceChanges(transaction, known, ourIcsd, ourCentralBank)) {
            // Registers see every movement but own no balance; their columns carry events only.
            if (!change.owner().equals(role.displayName())) {
              continue;
            }
            seen.add(asMoment(change));
            if (!change.given() && change.ours()) {
              netted
                  .computeIfAbsent(role.displayName(), ignored -> new LinkedHashMap<>())
                  .merge(change.unit() + "|" + change.cash(), change.delta(), BigDecimal::add);
            }
          }
          seen.addAll(moments(transaction));
          step.byNode()
              .computeIfAbsent(role.displayName(), ignored -> new ArrayList<>())
              .addAll(seen);
          // Only some nodes see the root, so the shallowest action across all views wins.
          seen.stream()
              .filter(m -> !m.actor().isEmpty())
              .min(Comparator.comparingInt(Moment::nodeId))
              .filter(m -> m.nodeId() < step.root()[0])
              .ifPresent(
                  m -> {
                    step.actor()[0] = m.actor();
                    step.root()[0] = m.nodeId();
                  });
        }
      }
    }

    List<Step> ordered = new ArrayList<>(steps.values());

    // Refusals are not on the ledger; they come from the application's own log.
    for (Refusals.Note refusal : Refusals.read()) {
      // The log is shared by processes and outlives deployments: skip an unknown role.
      String who;
      try {
        who = config.role(ActorRole.of(refusal.role()).key()).displayName();
      } catch (RuntimeException unknown) {
        continue;
      }
      Map<String, List<Moment>> seen = new LinkedHashMap<>();
      seen.put(
          who,
          List.of(
              new Moment(
                  "refused",
                  refusal.command(),
                  refusal.message(),
                  null,
                  who,
                  "",
                  "",
                  Integer.MAX_VALUE,
                  Integer.MAX_VALUE)));
      ordered.add(
          new Step(
              "refusal", refusal.at(), seen, new String[] {who}, new int[] {Integer.MAX_VALUE}));
    }
    ordered.sort(Comparator.comparing(Step::at));
    List<String> nodes = roles.stream().map(LedgerConfig.Role::displayName).toList();

    Map<String, List<Net>> net = new LinkedHashMap<>();
    netted.forEach(
        (who, byUnit) -> {
          List<Net> lines = new ArrayList<>();
          byUnit.forEach(
              (key, amount) -> {
                if (amount.signum() == 0) {
                  return;
                }
                String[] parts = key.split("\\|", 2);
                lines.add(new Net(parts[0], Boolean.parseBoolean(parts[1]), amount));
              });
          if (!lines.isEmpty()) {
            net.put(who, lines);
          }
        });

    return new Run(nodes, ordered, net);
  }

  private static List<Change> balanceChanges(
      Transaction transaction, Map<String, Holding> known, String icsd, String centralBank) {
    // Consuming nothing means a register created it (funding, issuance): nobody's profit.
    boolean given =
        transaction.getEvents().stream()
            .noneMatch(
                event ->
                    event instanceof ArchivedEvent
                        || (event instanceof ExercisedEvent exercised && exercised.isConsuming()));
    Map<String, BigDecimal> byOwner = new LinkedHashMap<>();
    Map<String, String> assetOf = new LinkedHashMap<>();
    // Which register an asset came from, so paper signed by somebody else's
    // register is not counted as this party's.
    Map<String, String> registerOf = new LinkedHashMap<>();

    for (Event event : transaction.getEvents()) {
      if (event instanceof CreatedEvent created) {
        String template = created.getTemplateId().getEntityName();
        String asset =
            template.equals("CashBalance") ? "cash"
                : template.equals("SecurityPosition") ? "paper" : "";
        if (asset.isEmpty()) {
          continue;
        }
        Map<String, Value> fields = created.getArguments().getFieldsMap();
        String owner = scalar(fields, "owner").map(LedgerConfig::displayName).orElse("");
        String size = scalar(fields, asset.equals("cash") ? "amount" : "quantity").orElse("0");
        String unit = asset.equals("cash") ? scalar(fields, "currency").orElse("") : scalar(fields, "isin").orElse("");
        String register =
            scalar(fields, asset.equals("cash") ? "centralBank" : "icsd")
                .map(LedgerConfig::displayName)
                .orElse("");
        Holding holding =
            new Holding(owner, unit, asset.equals("cash"), new BigDecimal(size), register);
        known.put(created.getContractId(), holding);
        byOwner.merge(owner + "|" + unit, holding.amount(), BigDecimal::add);
        assetOf.put(owner + "|" + unit, asset);
        registerOf.put(owner + "|" + unit, register);
      } else {
        String contractId =
            event instanceof ArchivedEvent archived
                ? archived.getContractId()
                : ((ExercisedEvent) event).isConsuming()
                    ? ((ExercisedEvent) event).getContractId()
                    : null;
        Holding gone = contractId == null ? null : known.get(contractId);
        if (gone != null) {
          byOwner.merge(gone.owner() + "|" + gone.unit(), gone.amount().negate(), BigDecimal::add);
          assetOf.put(gone.owner() + "|" + gone.unit(), gone.cash() ? "cash" : "paper");
          registerOf.put(gone.owner() + "|" + gone.unit(), gone.register());
        }
      }
    }

    List<Change> changes = new ArrayList<>();
    byOwner.forEach(
        (key, delta) -> {
          if (delta.signum() == 0) {
            return;
          }
          String[] parts = key.split("\\|", 2);
          boolean cash = "cash".equals(assetOf.get(key));
          String register = registerOf.getOrDefault(key, "");
          changes.add(
              new Change(
                  parts[0], parts[1], cash, delta, given, register,
                  register.equals(cash ? centralBank : icsd)));
        });
    return changes;
  }

  private record Change(
      String owner, String unit, boolean cash, BigDecimal delta, boolean given, String register,
      boolean ours) {}

  private static Moment asMoment(Change change) {
    String amount =
        (change.delta().signum() > 0 ? "+" : "-")
            + (change.cash()
                ? Amounts.money(change.delta().abs())
                : Amounts.quantity(change.delta().abs()));
    // Anything from another register is shown and named, never quietly counted.
    String whose = change.ours() ? "" : " (" + change.register() + "'s register)";
    return new Moment(
        "delta", change.owner(), amount + " " + change.unit() + whose, null, "", "", "", -1, -1);
  }

  private static List<Moment> moments(Transaction transaction) {
    List<Moment> moments = new ArrayList<>();
    for (Event event : transaction.getEvents()) {
      if (event instanceof CreatedEvent created) {
        // A plain create has no acting party; its signatories are the closest answer.
        String signedBy = names(created.getSignatories());
        String watchedBy = names(created.getObservers());
        moments.add(
            new Moment(
                "created",
                created.getTemplateId().getEntityName(),
                "",
                created.getArguments(),
                signedBy,
                watchedBy,
                created.getContractId(),
                created.getNodeId(),
                created.getNodeId()));
      } else if (event instanceof ArchivedEvent archived) {
        moments.add(
            new Moment(
                "archived",
                archived.getTemplateId().getEntityName(),
                "",
                null,
                "",
                "",
                archived.getContractId(),
                Integer.MAX_VALUE,
                Integer.MAX_VALUE));
      } else if (event instanceof ExercisedEvent exercised) {
        String actors = names(exercised.getActingParties());
        moments.add(
            new Moment(
                exercised.isConsuming() ? "consumed" : "exercised",
                exercised.getTemplateId().getEntityName(),
                exercised.getChoice(),
                exercised.getChoiceArgument().asRecord().orElse(null),
                actors,
                "",
                exercised.getContractId(),
                exercised.getNodeId(),
                exercised.getLastDescendantNodeId()));
      }
    }
    return moments;
  }

  private static String names(Collection<String> parties) {
    return parties.stream()
        .map(LedgerConfig::displayName)
        .distinct()
        .reduce((one, two) -> one + " + " + two)
        .orElse("");
  }

  static Optional<String> scalar(Map<String, Value> fields, String name) {
    Value value = fields.get(name);
    if (value == null) {
      return Optional.empty();
    }
    if (value.asText().isPresent()) {
      return value.asText().map(text -> text.getValue());
    }
    if (value.asParty().isPresent()) {
      return value.asParty().map(party -> party.getValue());
    }
    if (value.asNumeric().isPresent()) {
      return value.asNumeric().map(numeric -> numeric.getValue().toPlainString());
    }
    if (value.asDate().isPresent()) {
      return value.asDate().map(date -> date.getValue().toString());
    }
    return Optional.empty();
  }

  private Trace() {}
}
