package org.cha0scollective.powerdock;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

import java.util.Locale;

record FrameStyle(Kind kind, int side, int leftReach, int rightReach, boolean terminals) {
    enum Kind { FLOATING, CANTILEVER, PORTAL, WALL, CEILING }
    static final FrameStyle FLOATING = new FrameStyle(Kind.FLOATING, 1, 0, 0, false);
    static final FrameStyle PORTAL = new FrameStyle(Kind.PORTAL, 1, 0, 0, false);

    FrameStyle {
        side = side < 0 ? -1 : 1;
        leftReach = Mth.clamp(leftReach, -3, 8);
        rightReach = Mth.clamp(rightReach, kind == Kind.CEILING || kind == Kind.WALL ? 0 : -3, 8);
        if (kind == Kind.WALL) leftReach = Math.max(0, leftReach);
        if (kind == Kind.FLOATING) terminals = false;
    }
    boolean sided() { return kind == Kind.CANTILEVER || kind == Kind.WALL || kind == Kind.PORTAL; }
    int reach(int which) { return which < 0 ? leftReach : rightReach; }
    FrameStyle change(int button) {
        int step = button == 7 || button == 10 || button == 12 ? -1 : 1;
        return switch (button) {
            case 7, 8 -> new FrameStyle(Kind.values()[Math.floorMod(kind.ordinal() + step, Kind.values().length)],
                    side, 0, 0, terminals);
            case 9 -> new FrameStyle(kind, -side, leftReach, rightReach, terminals);
            case 10, 11 -> new FrameStyle(kind, side, leftReach + step, rightReach, terminals);
            case 12, 13 -> new FrameStyle(kind, side, leftReach, rightReach + step, terminals);
            case 14 -> new FrameStyle(kind, side, leftReach, rightReach, !terminals);
            default -> this;
        };
    }
    void write(CompoundTag tag) {
        tag.putString("Support", kind.name());
        tag.putInt("SupportSide", side);
        tag.putInt("LeftReach", leftReach);
        tag.putInt("RightReach", rightReach);
        tag.putBoolean("FeedPosts", terminals);
    }
    static FrameStyle read(CompoundTag tag) {
        if (!tag.contains("Support")) return PORTAL;
        Kind kind;
        try { kind = Kind.valueOf(tag.getString("Support")); }
        catch (IllegalArgumentException unknown) { kind = Kind.FLOATING; }
        return new FrameStyle(kind, tag.getInt("SupportSide"), tag.getInt("LeftReach"), tag.getInt("RightReach"), tag.getBoolean("FeedPosts"));
    }
    String label() { return "gui.powerdock.mount_" + kind.name().toLowerCase(Locale.ROOT); }
}
