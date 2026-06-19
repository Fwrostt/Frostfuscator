package dev.frost.obfuscator.dictionary;

public class UnicodeDictionary implements Dictionary {
    private int counter = 0;

    @Override
    public String next() {
        int val = counter++;
        StringBuilder sb = new StringBuilder();
        do {
            sb.append((char) ('\u2000' + (val % 26)));
            val /= 26;
        } while (val > 0);
        return sb.toString();
    }

    @Override
    public void reset() {
        counter = 0;
    }
}
