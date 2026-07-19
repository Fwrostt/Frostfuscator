package dev.frost.obfuscator.dictionary;

public class AlphabetDictionary implements Dictionary {

    private int counter = 0;

    @Override
    public String next() {
        String name = encode(counter++);
        return name;
    }

    @Override
    public void reset() {
        counter = 0;
    }

    private String encode(int value) {
        StringBuilder sb = new StringBuilder();
        do {
            sb.insert(0, (char) ('a' + (value % 26)));
            value = (value / 26) - 1;
        } while (value >= 0);
        return sb.toString();
    }
}
