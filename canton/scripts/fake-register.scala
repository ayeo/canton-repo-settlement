// CharlieBank registers an instrument in a register of its own and issues itself
// a billion of it. Both commands succeed: it is the sole signatory of that
// register, and nothing in Canton stops a party from signing its own paper.
//
// The paper is still worthless. Every check in the model compares the depository
// against the one the counterparties named, so it can never back a repo.

import com.daml.ledger.api.v2.commands.{Command, ExerciseCommand}
import com.daml.ledger.api.v2.value.{Record, RecordField, Value}

val charlieParty = charlie.parties.list(filterParty = "CharlieBank").head.party
val bravoParty = bravo.parties.list(filterParty = "BravoBank").head.party

val pkg = charlie.ledger_api.state.acs
  .of_party(charlieParty)
  .find(_.templateId.toString.contains("Instrument"))
  .get
  .templateId
  .packageId

val registered = charlie.ledger_api.commands.submit(
  Seq(charlieParty),
  Seq(
    ledger_api_utils.create(
      pkg,
      "Custody",
      "Instrument",
      Map(
        "icsd" -> charlieParty,
        "isin" -> "DE0001102580",
        "issuer" -> "CharlieBank's own register",
        "currency" -> "EUR",
        "price" -> 98.75,
        "observers" -> List()))))

val instrument = registered.events.head.getCreated
println(s"\n[fake] CharlieBank registered DE0001102580 in its own register: ${instrument.contractId}")

// Built by hand: the console's helper renders a Double as 1.0E9, which the
// ledger will not read as a Numeric.
val issue = Command(
  Command.Command.Exercise(
    ExerciseCommand(
      templateId = instrument.templateId,
      contractId = instrument.contractId,
      choice = "Issue",
      choiceArgument = Some(
        Value(
          Value.Sum.Record(
            Record(fields = Seq(
              RecordField("owner", Some(Value(Value.Sum.Party(bravoParty.toLf)))),
              RecordField("quantity", Some(Value(Value.Sum.Numeric("1000000000.0"))))))))))))

val issued = charlie.ledger_api.commands.submit(Seq(charlieParty), Seq(issue))

println(s"[fake] and issued 1kkk of it to BravoBank, which never asked for it: ${issued.events.head.getCreated.contractId}")
println("")
println("Both went through, and neither touched the ICSD's book: a party may always")
println("sign its own register. What makes paper worth something is whose register")
println("it is in, and the model checks that at every step:")
println("")
println("  AcceptProposal: listed.icsd == icsd, hold.icsd == icsd, schedule.icsd == icsd")
println("  SettleOpening:  hold.icsd == icsd")
println("")
println("A lender's published schedule names the depository it trusts, so this bond")
println("cannot back a repo with AlphaBank or BravoBank. Run make check to see the")
println("the position sitting in BravoBank's node - visible, and worth nothing.")
println("")

sys.exit(0)
