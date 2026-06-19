package dev.frost.obfuscator.dictionary;

public class NumericDictionary implements Dictionary {
    private int counter = 0;

    @Override
    public String next() {
        return "_" + (counter++);
    }

    @Override
    public void reset() {
        counter = 0;
    }
}
