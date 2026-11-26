package org.cha0scollective.powerdock.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.cha0scollective.powerdock.CatenaryContacts;
import org.cha0scollective.powerdock.CatenaryContacts.Terminals;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.CurrentSourceWire;

@GameTestHolder("powerdock_loconautics_tests")
@PrefixGameTestTemplate(false)
public final class CatenaryCircuitGameTest {
    @GameTest(template = "empty", timeoutTicks = 150)
    public static void parallelCatenaryFeedsShareCurrent(GameTestHelper test) {
        var level = test.getLevel();
        var westPost = placePosts(test, new BlockPos(0, 1, 0));
        var eastPost = placePosts(test, new BlockPos(2, 1, 0));
        var trainPost = placePosts(test, new BlockPos(4, 1, 0));
        var west = Terminals.virtual(Vec3.atCenterOf(test.absolutePos(new BlockPos(1, 1, 0))));
        var east = Terminals.virtual(Vec3.atCenterOf(test.absolutePos(new BlockPos(3, 1, 0))));
        var westSource = source(level, westPost, 24);
        var eastSource = source(level, eastPost, 24);
        var westFeed = connect(test, westPost, west, 1);
        var eastFeed = connect(test, eastPost, east, 1);
        var main = connect(test, west, east, 2);
        var siding = connect(test, west, east, 4);
        var pickup = connect(test, east, trainPost, 0.08);
        var grid = GlobalElectricNetworks.getWorldNetworks(level);
        var load = grid.makeSimpleWire(trainPost.supply(), trainPost.returnWire(), 9.92f);
        test.assertTrue(load != null, "The test train load failed to attach");

        test.runAtTickTime(20, () -> {
            near(test, voltage(level, east), 22.4299, "voltage at the east turnout");
            near(test, westFeed.supply().current(), 0.6729, "west feeder current");
            near(test, eastFeed.supply().current(), 1.5701, "east feeder current");
            near(test, main.supply().current(), 0.4486, "main current");
            near(test, siding.supply().current(), 0.2243, "siding current");
            eastFeed.open();
            main.open();
        });
        test.runAtTickTime(40, () -> {
            near(test, load.current(), 1.6, "train current through the siding");
            near(test, voltage(level, east), 16, "voltage with the main and east feeder open");
            siding.open();
        });
        test.runAtTickTime(60, () -> {
            near(test, load.current(), 0, "train current after both routes were opened");
            pickup.open();
            load.remove();
            eastSource.setCurrent(20000);
            connect(test, west, east, 4);
            connect(test, eastPost, east, 1);
        });
        test.runAtTickTime(80, () -> {
            near(test, westFeed.supply().current(), 2.0 / 3, "backfeed with unequal supplies and no train");
            var westRun = grid.globalGraph.getWires(west.supply().getNode(level)).stream()
                    .filter(wire -> wire != westFeed.supply()).findFirst().orElseThrow();
            near(test, westRun.current(), 2.0 / 3, "siding current after reconnecting the feeds");
            for (var terminals : new Terminals[]{west, east}) {
                var positive = terminals.supply().getNode(level);
                var negative = terminals.returnWire().getNode(level);
                while (!grid.globalGraph.getWires(positive).isEmpty()) grid.globalGraph.getWires(positive).getFirst().remove();
                while (!grid.globalGraph.getWires(negative).isEmpty()) grid.globalGraph.getWires(negative).getFirst().remove();
                terminals.retire(level);
                test.assertTrue(!grid.globalExternalNodes.containsKey(terminals.supply())
                                && !grid.globalExternalNodes.containsKey(terminals.returnWire()),
                        "Power Grid retained a retired overhead node");
            }
            westSource.remove();
            eastSource.remove();
            test.succeed();
        });
    }

    private static Terminals placePosts(GameTestHelper test, BlockPos position) {
        var hanger = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("powerdock:catenary_hanger"));
        test.setBlock(position, hanger);
        return Terminals.block(test.absolutePos(position));
    }

    // Norton equivalent of a voltage source with a small internal resistance.
    private static CurrentSourceWire source(ServerLevel level, Terminals terminals, double voltage) {
        var grid = GlobalElectricNetworks.getWorldNetworks(level);
        var network = grid.prepareForConnection(terminals.supply(), terminals.returnWire());
        var source = new CurrentSourceWire(terminals.supply().getNode(level), terminals.returnWire().getNode(level), 1000.0);
        source.setCurrent(voltage * 1000);
        network.addWire(source);
        return source;
    }

    private static CatenaryContacts connect(GameTestHelper test, Terminals from, Terminals to, double roundTrip) {
        float perConductor = (float) (roundTrip / 2);
        var wires = CatenaryContacts.close(test.getLevel(), from, to, perConductor, perConductor);
        test.assertTrue(wires != null, "A paired catenary connection failed");
        return wires;
    }

    private static double voltage(ServerLevel level, Terminals terminals) {
        return terminals.supply().getNode(level).getVoltage() - terminals.returnWire().getNode(level).getVoltage();
    }

    private static void near(GameTestHelper test, double actual, double expected, String name) {
        test.assertTrue(Math.abs(actual - expected) < 0.004, name + " was " + actual + ", expected " + expected);
    }
}
