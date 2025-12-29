package org.example.operators;

import java.util.List;
import java.util.Optional;

public class Utils {
  static Optional<String> getLabelValueByLabelName(
      List<String> symbols,
      List<Integer> refs,
      String labelName) {
    for (int i = 0; i < refs.size(); i += 2) {
      if (labelName.equals(symbols.get(refs.get(i)))) {
        return Optional.of(symbols.get(refs.get(i + 1)));
      }
    }
    return Optional.empty();
  }

  static int getOrAddSymbol(List<String> symbols, String symbol) {
    int idx = symbols.indexOf(symbol);
    if (idx >= 0) {
      return idx;
    }
    symbols.add(symbol);
    return symbols.size() - 1;
  }
}
