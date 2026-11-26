package org.cha0scollective.powerdock;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.cha0scollective.powerdock.CatenaryContacts.Terminals;
import org.cha0scollective.powerdock.CatenarySpan.Distance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Maintains the PG resistors of one physical pair of wires, including moving pickups. */
final class CatenaryRun {
    private static final double TOUCH = 1e-4;
    private static final float MIN_RESISTANCE = 1e-7f;
    private static final float SHOE_RESISTANCE = 0.04f;

    private final Distance end;
    private final ServerLevel level;
    private final Terminals from;
    private final Terminals to;
    private final double resistancePerBlock;
    private final Map<BlockPos, Pickup> pickups = new HashMap<>();
    private final Map<Section, CatenaryContacts> connections = new HashMap<>();
    private Set<Terminals> splitPoints = Set.of();

    CatenaryRun(ServerLevel level, Distance end, Terminals from, Terminals to, double resistancePerBlock) {
        this.level = level;
        this.end = end;
        this.from = from;
        this.to = to;
        this.resistancePerBlock = resistancePerBlock;
    }

    void move(BlockPos pickup, Distance at, Vec3 position) {
        var tap = pickups.computeIfAbsent(pickup, unused -> new Pickup());
        tap.at = at;
        tap.position = position;
    }

    void release(BlockPos pickup) {
        var removed = pickups.remove(pickup);
        if (removed != null) removed.disconnect();
    }

    boolean livePickup(BlockPos pickup) {
        var plate = pickups.get(pickup);
        return plate != null && plate.contacts != null && plate.contacts.live();
    }

    void update() {
        var sorted = new ArrayList<>(pickups.entrySet());
        sorted.sort(Comparator.<Map.Entry<BlockPos, Pickup>>comparingDouble(entry -> entry.getValue().at.center())
                .thenComparingLong(entry -> entry.getKey().asLong()));
        Map<Section, Distance> desired = new HashMap<>();
        Set<Terminals> retained = new HashSet<>();
        Terminals previous = from;
        Distance start = Distance.ZERO;
        for (int i = 0; i < sorted.size();) {
            int first = i++;
            var pickup = sorted.get(first).getValue();
            var position = pickup.at;
            while (i < sorted.size() && sorted.get(i).getValue().at.center() - position.center() <= TOUCH) i++;
            Terminals tap;
            if (position.center() <= TOUCH) tap = from;
            else if (end.center() - position.center() <= TOUCH) tap = to;
            else {
                tap = null;
                for (int member = first; member < i && tap == null; member++) {
                    var old = sorted.get(member).getValue().terminals;
                    if (old != null && splitPoints.contains(old) && !retained.contains(old)) tap = old;
                }
                if (tap == null) tap = Terminals.virtual(pickup.position);
                retained.add(tap);
                desired.put(new Section(previous, tap), position.minus(start));
                previous = tap;
                start = position;
            }
            for (int member = first; member < i; member++) {
                var entry = sorted.get(member);
                entry.getValue().attach(level, entry.getKey(), tap);
            }
        }
        if (!previous.equals(to)) desired.put(new Section(previous, to), end.minus(start));
        connections.entrySet().removeIf(entry -> {
            if (desired.containsKey(entry.getKey()) && entry.getValue().live()) return false;
            entry.getValue().open();
            return true;
        });
        desired.forEach((section, length) -> {
            float supply = resistance(length.supply());
            float returnWire = resistance(length.returnWire());
            var wire = connections.get(section);
            if (wire == null) {
                var connection = CatenaryContacts.close(level, section.from, section.to, supply, returnWire);
                if (connection != null) connections.put(section, connection);
            } else {
                if (wire.supply().getResistance() != supply) wire.supply().setResistance(supply);
                if (wire.returnWire().getResistance() != returnWire) wire.returnWire().setResistance(returnWire);
            }
        });
        for (var terminal : splitPoints) if (!retained.contains(terminal)) terminal.retire(level);
        splitPoints = retained;
    }

    void close() {
        pickups.values().forEach(Pickup::disconnect);
        pickups.clear();
        connections.values().forEach(CatenaryContacts::open);
        connections.clear();
        for (var terminals : splitPoints) terminals.retire(level);
        splitPoints = Set.of();
    }

    private float resistance(double length) {
        return Math.max(MIN_RESISTANCE, (float) (length * resistancePerBlock));
    }

    private record Section(Terminals from, Terminals to) {}

    private static final class Pickup {
        Distance at;
        Vec3 position;
        Terminals terminals;
        CatenaryContacts contacts;

        void attach(ServerLevel level, BlockPos pos, Terminals tap) {
            if (tap.equals(terminals) && contacts != null && contacts.live()) return;
            disconnect();
            terminals = tap;
            contacts = CatenaryContacts.close(level, tap, Terminals.block(pos), SHOE_RESISTANCE, SHOE_RESISTANCE);
        }

        void disconnect() {
            if (contacts != null) contacts.open();
            contacts = null;
            terminals = null;
        }
    }
}
