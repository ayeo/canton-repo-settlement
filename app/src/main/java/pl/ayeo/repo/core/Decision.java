package pl.ayeo.repo.core;

import com.daml.ledger.javaapi.data.DisclosedContract;
import com.daml.ledger.javaapi.data.codegen.Update;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import pl.ayeo.repo.ledger.Party;

public record Decision(
    String says,
    List<Update<?>> commands,
    List<Showing> showing,
    List<DisclosedContract> using,
    Object rejectionContext) {

  public Decision(String says, Update<?> command) {
    this(says, List.of(command), List.of(), List.of(), null);
  }

  public Decision(String says, List<Update<?>> commands) {
    this(says, List.copyOf(commands), List.of(), List.of(), null);
  }

  public static Decision nothing(String why) {
    return new Decision(why, List.<Update<?>>of(), List.of(), List.of(), null);
  }

  public boolean acted() {
    return !commands.isEmpty();
  }

  public Decision showing(ContractRef contractId, Party to) {
    List<Showing> more = new ArrayList<>(showing);
    more.add(new Showing(contractId, to));
    return new Decision(says, commands, List.copyOf(more), using, rejectionContext);
  }

  public Decision using(DisclosedContract contract) {
    List<DisclosedContract> more = new ArrayList<>(using);
    more.add(contract);
    return new Decision(says, commands, showing, List.copyOf(more), rejectionContext);
  }

  public Decision inResponseTo(Object context) {
    return new Decision(says, commands, showing, using, context);
  }

  public record Showing(ContractRef contractId, Party to) {}

  public static <T> Decision provided(
      Optional<T> present, String otherwise, Function<T, Decision> decide) {
    return present.map(decide).orElseGet(() -> nothing(otherwise));
  }
}
