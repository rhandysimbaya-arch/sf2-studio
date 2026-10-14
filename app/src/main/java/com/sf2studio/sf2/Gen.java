package com.sf2studio.sf2;

/** One generator: operator + raw 16-bit amount (unsigned storage). */
public final class Gen {
    public int oper;
    public int amount;

    public Gen(int oper, int amount) {
        this.oper = oper;
        this.amount = amount & 0xFFFF;
    }

    public static Gen range(int oper, int lo, int hi) {
        return new Gen(oper, ((hi & 0xFF) << 8) | (lo & 0xFF));
    }

    public int lo() {
        return amount & 0xFF;
    }

    public int hi() {
        return (amount >> 8) & 0xFF;
    }

    public short signed() {
        return (short) amount;
    }

    public Gen copy() {
        return new Gen(oper, amount);
    }
}
