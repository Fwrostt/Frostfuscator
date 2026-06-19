package dev.frost.obfuscator.dictionary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FileDictionary implements Dictionary {

    private final List<String> names;
    private int index = 0;
    private final AlphabetDictionary fallback = new AlphabetDictionary();

    public FileDictionary(Path filePath) {
        List<String> loaded = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(filePath);
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    loaded.add(trimmed);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load dictionary file: " + filePath, e);
        }

        if (loaded.isEmpty()) {
            throw new RuntimeException("Dictionary file is empty: " + filePath);
        }

        this.names = loaded;
    }

    @Override
    public String next() {
        if (index < names.size()) {
            return names.get(index++);
        }
        return fallback.next();
    }

    @Override
    public void reset() {
        index = 0;
        fallback.reset();
    }
}
