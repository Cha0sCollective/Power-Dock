local function waitFor(check, failure)
    for _ = 1, 90 do
        local result = check()
        if result then return result end
        sleep(0.05)
    end
    error(failure)
end

local function report(name, text)
    local path = name .. ".tmp"
    local file = assert(fs.open(path, "w"))
    file.write(text)
    file.close()
    fs.move(path, name)
end

local function run()
    local dock = waitFor(function() return peripheral.find("power_dock") end, "Missing carrier dock")
    local modem = waitFor(function() return peripheral.find("modem") end, "Missing carrier modem")
    local function remote(kind)
        for _, name in ipairs(modem.getNamesRemote()) do
            if modem.hasTypeRemote(name, kind) then return name end
        end
    end
    assert(not modem.isWireless(), "Expected a wired modem")
    assert(dock.getContactCount() == 9, "Expected eight pins and common")
    assert(dock.getState() == "disarmed", "Carrier Pin Dock should start disarmed")
    assert(not remote("inventory"), "The plane was visible before docking")
    assert(not pcall(dock.getVoltage, 10), "An invalid terminal was accepted")
    dock.setArmed(true)
    waitFor(function() return dock.getState() == "docked" end, "Pin Dock did not latch from Lua")
    local cargo = waitFor(function() return remote("inventory") end, "The plane inventory did not join the wired network")
    local items = modem.callRemote(cargo, "list")
    assert(items[1].name == "minecraft:copper_ingot" and items[1].count == 3, "Wrong items from the plane")
    local planeDock = waitFor(function() return remote("power_dock") end, "The plane's dock peripheral did not join")
    assert(modem.callRemote(planeDock, "getState") == "docked", "The plane dock did not answer")
    local voltage = dock.getVoltage(3)
    assert(voltage and math.abs(voltage) > 10, "Pin 3 voltage was missing")
    assert(math.abs(dock.getVoltage(9, 3) + voltage) < 0.001, "Common reference is wired incorrectly")

    report("ready-unload", "ok")
    waitFor(function() return not remote("inventory") and dock.getState() ~= "docked" end,
        "The unloaded plane stayed visible on the network")
    report("observed-unload", "ok")
    cargo = waitFor(function() return dock.getState() == "docked" and remote("inventory") end,
        "The reloaded plane did not join the network again")
    assert(modem.callRemote(cargo, "list")[1].count == 3, "The reloaded plane did not answer")

    dock.release()
    waitFor(function() return not remote("inventory") end, "The carrier could still see the released network")
    assert(dock.getState() == "disarmed", "Release should leave the dock disarmed")
    dock.setArmed(true)
    waitFor(function() return dock.getState() == "docked" and remote("inventory") end, "The released dock could not reconnect")
    assert(modem.callRemote(cargo, "list")[1].count == 3, "The plane did not answer after redocking")
    dock.useRedstone()
    waitFor(function() return not remote("inventory") end, "Redstone mode did not release the unpowered dock")
end

local ok, failure = pcall(run)
report("result.txt", ok and "ok" or tostring(failure))
