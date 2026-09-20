package pl.ayeo.repo.core.render;

import com.daml.ledger.javaapi.data.DamlOptional;
import com.daml.ledger.javaapi.data.DamlRecord;
import com.daml.ledger.javaapi.data.Value;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import pl.ayeo.repo.actors.domain.Currency;
import pl.ayeo.repo.actors.domain.Isin;
import pl.ayeo.repo.actors.domain.Terms;
import pl.ayeo.repo.actors.domain.TradeId;
import pl.ayeo.repo.ledger.LedgerConfig;
import pl.ayeo.repo.core.observer.Trace;
import pl.ayeo.repo.core.observer.Trace.Moment;
import pl.ayeo.repo.core.observer.Trace.Step;

public final class Table {

  public static String of(Trace.Run run) {
    return asJson(run.nodes(), run.steps(), run.net());
  }

  private static String asJson(
      List<String> nodes, List<Step> steps, Map<String, List<Trace.Net>> net) {
    StringBuilder out = new StringBuilder("{\n  \"nodes\": [");
    for (int i = 0; i < nodes.size(); i++) {
      out.append(i == 0 ? "" : ", ").append(quote(nodes.get(i)));
    }
    out.append("],\n  \"steps\": [\n");
    for (int i = 0; i < steps.size(); i++) {
      Step step = steps.get(i);
      out.append("    {\"id\": ").append(quote(step.updateId()))
          .append(", \"at\": ").append(quote(step.at()))
          .append(", \"actor\": ").append(quote(step.actor()[0]))
          .append(", \"seen\": {");
      boolean first = true;
      for (Map.Entry<String, List<Moment>> entry : step.byNode().entrySet()) {
        if (!first) {
          out.append(", ");
        }
        first = false;
        out.append(quote(entry.getKey())).append(": [");
        for (int m = 0; m < entry.getValue().size(); m++) {
          Moment moment = entry.getValue().get(m);
          out.append(m == 0 ? "" : ", ")
              .append("{\"kind\": ").append(quote(moment.kind()))
              .append(", \"template\": ").append(quote(moment.template()))
              .append(", \"detail\": ").append(quote(describe(moment)))
              .append(", \"actor\": ").append(quote(moment.actor()))
              .append(", \"watchedBy\": ").append(quote(moment.watchedBy()))
              .append(", \"contract\": ").append(quote(moment.contractId()))
              .append(", \"depth\": ").append(Trace.depth(moment, entry.getValue())).append("}");
        }
        out.append("]");
      }
      out.append("}}").append(i == steps.size() - 1 ? "\n" : ",\n");
    }
    out.append("  ],\n  \"net\": {");
    boolean first = true;
    for (Map.Entry<String, List<Trace.Net>> entry : net.entrySet()) {
      if (!first) {
        out.append(", ");
      }
      first = false;
      out.append(quote(entry.getKey())).append(": [");
      for (int n = 0; n < entry.getValue().size(); n++) {
        Trace.Net line = entry.getValue().get(n);
        out.append(n == 0 ? "" : ", ").append(quote(netted(line)));
      }
      out.append("]");
    }
    return out.append("}\n}\n").toString();
  }

  private static String netted(Trace.Net line) {
    return (line.amount().signum() > 0 ? "+" : "-")
        + (line.cash() ? Amounts.money(line.amount().abs()) : Amounts.quantity(line.amount().abs()))
        + " "
        + line.unit();
  }

  private static String quote(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String describe(Moment moment) {
    boolean call = moment.kind().equals("consumed") || moment.kind().equals("exercised");
    if (call) {
      return moment.detail()
          + "("
          + (moment.payload() == null ? "" : args(moment.template(), moment.payload()))
          + ")";
    }
    return moment.payload() == null ? moment.detail() : detail(moment.payload());
  }

  private static String args(String template, DamlRecord arguments) {
    List<String> parts = new ArrayList<>();
    for (DamlRecord.Field field : arguments.getFields()) {
      String name = field.getLabel().orElse("");
      said(template, name, field.getValue()).ifPresent(value -> parts.add(name + ": " + value));
    }
    return String.join(", ", parts);
  }

  private static Optional<String> said(String template, String name, Value value) {
    if (value.asList().isPresent()) {
      List<String> each =
          value.asList().orElseThrow().stream()
              .map(item -> said(template, name, item).orElse(""))
              .filter(text -> !text.isEmpty())
              .toList();
      return each.isEmpty() ? Optional.empty() : Optional.of(String.join(" + ", each));
    }
    if (value.asParty().isPresent()) {
      return value.asParty().map(party -> LedgerConfig.displayName(party.getValue()));
    }
    if (value.asContractId().isPresent()) {
      return value.asContractId().map(id -> shortContract(id.getValue()));
    }
    if (value.asNumeric().isPresent()) {
      BigDecimal number = value.asNumeric().orElseThrow().getValue();
      return Optional.of(cash(template) ? Amounts.money(number) : Amounts.quantity(number));
    }
    return scalar(Map.of(name, value), name);
  }

  private static boolean cash(String template) {
    return template.contains("Cash") || template.contains("Balance");
  }

  private static String shortContract(String id) {
    String body = id.startsWith("00") ? id.substring(2) : id;
    return "#" + body.substring(0, Math.min(5, body.length()));
  }

  private static String detail(DamlRecord arguments) {
    Map<String, Value> fields = arguments.getFieldsMap();
    List<String> parts = new ArrayList<>();
    owner(fields).ifPresent(parts::add);
    scalar(fields, "quantity").ifPresent(q -> parts.add(Amounts.quantity(new BigDecimal(q))));
    scalar(fields, "amount")
        .ifPresent(
            a ->
                parts.add(
                    Amounts.money(new BigDecimal(a)) + " " + scalar(fields, "currency").orElse("")));
    // Who the hold is for: the taker once a quote has been taken, the counterparty
    // on cash. A contested hold says so rather than saying nothing.
    if (fields.containsKey("takenBy")) {
      parts.add("to " + takenBy(fields).orElse("nobody yet"));
    }
    scalar(fields, "counterparty")
        .map(LedgerConfig::displayName)
        .ifPresent(who -> parts.add("to " + who));
    // The ISIN only where it identifies something new; every position in this
    // deployment is the same bond.
    if (!fields.containsKey("terms") && !fields.containsKey("owner")) {
      scalar(fields, "isin").ifPresent(parts::add);
    }
    scalar(fields, "current").ifPresent(parts::add);
    scalar(fields, "reference").ifPresent(r -> parts.add("for " + r));
    terms(fields).ifPresent(parts::add);
    schedule(fields).ifPresent(parts::add);
    return String.join(", ", parts).trim();
  }

  private static Optional<String> takenBy(Map<String, Value> fields) {
    Value value = fields.get("takenBy");
    if (value == null) {
      return Optional.empty();
    }
    return value
        .asOptional()
        .flatMap(DamlOptional::getValue)
        .flatMap(Value::asParty)
        .map(party -> LedgerConfig.displayName(party.getValue()));
  }

  private static Optional<String> owner(Map<String, Value> fields) {
    Optional<String> holder = scalar(fields, "owner").map(LedgerConfig::displayName);
    if (holder.isPresent()) {
      return holder;
    }
    Optional<String> seller = scalar(fields, "seller").map(LedgerConfig::displayName);
    if (seller.isPresent()) {
      return seller.map(name -> "from " + name);
    }
    return scalar(fields, "buyer").map(LedgerConfig::displayName).map(name -> "by " + name);
  }

  private static Optional<String> terms(Map<String, Value> fields) {
    Value terms = fields.get("terms");
    if (terms == null) {
      return Optional.empty();
    }
    Optional<Map<String, Value>> record = terms.asRecord().map(DamlRecord::getFieldsMap);
    if (record.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Value> inner = record.orElseThrow();

    // Which trade, how much cash, at what rate; the rest is a command away.
    List<String> parts = new ArrayList<>();
    scalar(inner, "tradeId").ifPresent(parts::add);
    cash(inner).ifPresent(parts::add);
    scalar(inner, "repoRate").ifPresent(r -> parts.add("at " + percent(r)));
    return Optional.of(String.join(", ", parts));
  }

  private static Optional<String> schedule(Map<String, Value> fields) {
    Value eligible = fields.get("eligible");
    if (eligible == null) {
      return Optional.empty();
    }
    List<String> parts = new ArrayList<>();
    String lends =
        scalar(fields, "maxCashAmount")
            .map(max -> "lends up to " + Amounts.money(new BigDecimal(max)) + " " + scalar(fields, "currency").orElse(""))
            .orElse("lends");
    parts.add(scalar(fields, "indicativeRate").map(rate -> lends + " at " + percent(rate)).orElse(lends));
    eligible
        .asList()
        .ifPresent(
            list ->
                list.stream()
                    .forEach(
                        item ->
                            item.asRecord()
                                .map(DamlRecord::getFieldsMap)
                                .ifPresent(entry -> parts.add(admits(entry)))));
    scalar(fields, "validUntil").ifPresent(until -> parts.add("until " + until));
    return Optional.of(String.join(", ", parts));
  }

  private static String admits(Map<String, Value> entry) {
    return "takes "
        + scalar(entry, "isin").orElse("")
        + ": at most "
        + scalar(entry, "maxQuantity").map(q -> Amounts.quantity(new BigDecimal(q))).orElse("")
        + ", haircut from "
        + scalar(entry, "minHaircut").map(Table::percent).orElse("");
  }

  private static Optional<String> cash(Map<String, Value> terms) {
    try {
      BigDecimal quantity = new BigDecimal(scalar(terms, "quantity").orElseThrow());
      BigDecimal price = new BigDecimal(scalar(terms, "price").orElseThrow());
      BigDecimal haircut = new BigDecimal(scalar(terms, "haircut").orElseThrow());
      BigDecimal rate = new BigDecimal(scalar(terms, "repoRate").orElseThrow());
      LocalDate from = LocalDate.parse(scalar(terms, "purchaseDate").orElseThrow());
      LocalDate to = LocalDate.parse(scalar(terms, "repurchaseDate").orElseThrow());

      return Optional.of(
          Amounts.money(
              new Terms(
                      new TradeId(scalar(terms, "tradeId").orElse("unknown")),
                      new Isin("-"),
                      quantity,
                      price,
                      haircut,
                      new Currency(scalar(terms, "currency").orElse("EUR")),
                      rate,
                      from,
                      from,
                      to)
                  .purchasePrice())
              + " EUR");
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private static String percent(String rate) {
    return new BigDecimal(rate).movePointRight(2).stripTrailingZeros().toPlainString() + "%";
  }

  private static Optional<String> scalar(Map<String, Value> fields, String name) {
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

  private Table() {}
}
