package dev.frost.obfuscator.dictionary;

import java.nio.file.Paths;

public interface Dictionary {
    String next();
    void reset();

    static Dictionary create(String type) {
        if (type == null) {
            return new AlphabetDictionary();
        }
        switch (type.toLowerCase()) {
            case "numeric":
                return new NumericDictionary();
            case "unicode":
                return new UnicodeDictionary();
            case "alphabet":
                return new AlphabetDictionary();
            default:
                if (type.startsWith("file:")) {
                    return new FileDictionary(Paths.get(type.substring(5)));
                }
                return new AlphabetDictionary();
        }
    }
}
