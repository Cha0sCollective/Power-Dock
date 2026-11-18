package org.cha0scollective.powerdock;

// Keep optional CC:Tweaked types out of the core block and its saved state.
interface DockComputer {
    void tick();

    void connect(DockComputer peer);

    void disconnect(DockComputer peer);

    void remove();
}
