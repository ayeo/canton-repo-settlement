package pl.ayeo.repo.core;

import com.daml.ledger.javaapi.data.CreatedEvent;
import com.daml.ledger.javaapi.data.Identifier;
import com.daml.ledger.javaapi.data.codegen.Contract;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import pl.ayeo.repo.model.calendar.BusinessDate;

final class Codegen {

  private final Map<Identifier, Optional<Method>> readers = new HashMap<>();

  Optional<Contract<?, ?>> read(CreatedEvent event) {
    Optional<Method> reader = readers.computeIfAbsent(event.getTemplateId(), Codegen::readerFor);
    if (reader.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of((Contract<?, ?>) reader.get().invoke(null, event));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot read " + event.getTemplateId(), e);
    }
  }

  private static Optional<Method> readerFor(Identifier template) {
    String generated = BusinessDate.class.getPackageName();
    String root = generated.substring(0, generated.lastIndexOf('.'));
    String name =
        root
            + "."
            + template.getModuleName().toLowerCase(Locale.ROOT)
            + "."
            + template.getEntityName()
            + "$Contract";
    try {
      return Optional.of(Class.forName(name).getMethod("fromCreatedEvent", CreatedEvent.class));
    } catch (ClassNotFoundException | NoSuchMethodException noSuchTemplate) {
      return Optional.empty();
    }
  }
}
