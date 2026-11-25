package dev.aiarena;

import com.google.gson.*;
import java.util.*;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;

final class Plan {
    static final List<String> KEYS = Collections.unmodifiableList(Arrays.asList(
        "forward", "back", "left", "right", "jump", "sprint", "sneak", "attack", "use"));
    final String agent;
    final List<Step> steps = new ArrayList<>();
    final String type;
    double blocks;
    String item;
    int count;

    Plan(JsonObject j) {
        check(integer(j,"version",1,1) == 1, "version");
        agent = string(j,"agent");
        check(!agent.trim().isEmpty() && agent.length() <= 256,"agent");
        JsonArray actions = j.getAsJsonArray("steps");
        check(actions != null && actions.size() > 0 && actions.size() <= 100000, "steps");
        long total = 0;
        for (JsonElement raw : actions) {
            Step s = new Step(raw.getAsJsonObject());
            total += s.ticks;
            check(total <= 1200000, "duration");
            steps.add(s);
        }
        JsonObject g = j.getAsJsonObject("goal");
        type = string(g,"type");
        switch (type) {
            case "displacement":
                blocks = number(g,"blocks");
                check(blocks > 0, "blocks");
                break;
            case "item":
                item = string(g,"item");
                check(item.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+") && Registry.ITEM.containsId(new Identifier(item)), "item");
                count = integer(g,"count",1,Integer.MAX_VALUE);
                break;
            case "dragon_exit": break;
            default: throw new IllegalArgumentException("goal.type");
        }
    }

    static final class Step {
        final int ticks;
        final boolean[] keys = new boolean[KEYS.size()];
        final Float yaw, pitch;
        final int slot;
        final String target, ui, clickMode;
        final int clickSlot, clickButton;
        Step(JsonObject j) {
            ticks = integer(j,"ticks",1,1200000);
            JsonArray k = j.getAsJsonArray("keys");
            check(k != null, "keys");
            for (JsonElement e : k) {
                check(e.isJsonPrimitive() && e.getAsJsonPrimitive().isString(), "key");
                int i = KEYS.indexOf(e.getAsString());
                check(i >= 0 && !keys[i], "key");
                keys[i] = true;
            }
            yaw = j.has("yaw") ? (float) (number(j,"yaw") % 360) : null;
            pitch = j.has("pitch") ? (float) number(j,"pitch") : null;
            check(pitch == null || (pitch >= -90 && pitch <= 90), "pitch");
            slot = j.has("slot") ? integer(j,"slot",0,8) : -1;
            target = j.has("require_target") ? string(j,"require_target") : null;
            check(target == null || Arrays.asList("block","entity","miss").contains(target),"require_target");
            ui = j.has("ui") ? string(j,"ui") : null;
            check(ui == null || Arrays.asList("inventory","close").contains(ui),"ui");
            if (j.has("click")) {
                JsonObject c = j.getAsJsonObject("click");
                check(ui == null && c.entrySet().size() == 3,"click");
                clickSlot = integer(c,"slot",0,32767);
                clickButton = integer(c,"button",0,1);
                clickMode = string(c,"action");
                check(Arrays.asList("pickup","quick_move").contains(clickMode),"click.action");
            } else {
                clickSlot = clickButton = -1;
                clickMode = null;
            }
            if (ui != null || clickMode != null)
                check(k.size()==0 && target==null && slot<0 && yaw==null && pitch==null,"ui_input_conflict");
        }
    }

    static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        check(e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString(),key);
        return e.getAsString();
    }
    static double number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        check(e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber(),key);
        double v = e.getAsDouble();
        check(Double.isFinite(v),key);
        return v;
    }
    static int integer(JsonObject o, String key, int min, int max) {
        double n = number(o,key);
        check(n == Math.rint(n) && n >= min && n <= max,key);
        return (int)n;
    }
    static void check(boolean ok, String what) {
        if (!ok) throw new IllegalArgumentException(what);
    }
}
