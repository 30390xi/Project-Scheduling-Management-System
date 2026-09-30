package com.company.scheduler.task.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 同项目 FS 依赖有向图。纯函数，用于保存依赖前的环路识别（AC-08）。 */
public final class DependencyGraph {
    private DependencyGraph() {}

    public record Edge(UUID predecessor, UUID successor) {}

    /** 返回一条环路径（含起终点），无环时为 empty。结果对相同输入稳定。 */
    public static Optional<List<UUID>> findCycle(Collection<Edge> edges) {
        Map<UUID, List<UUID>> outgoing = new HashMap<>();
        Set<UUID> nodes = new HashSet<>();
        for (Edge edge : edges) {
            outgoing.computeIfAbsent(edge.predecessor(), k -> new ArrayList<>()).add(edge.successor());
            nodes.add(edge.predecessor());
            nodes.add(edge.successor());
        }
        outgoing.values().forEach(list -> list.sort(Comparator.comparing(UUID::toString)));
        List<UUID> sortedNodes = nodes.stream().sorted(Comparator.comparing(UUID::toString)).toList();

        Map<UUID, Integer> color = new HashMap<>(); // 0=未访问 1=在栈中 2=完成
        for (UUID start : sortedNodes) {
            if (color.getOrDefault(start, 0) != 0) continue;
            var cycle = dfs(start, outgoing, color);
            if (cycle.isPresent()) return cycle;
        }
        return Optional.empty();
    }

    private static Optional<List<UUID>> dfs(UUID start, Map<UUID, List<UUID>> outgoing, Map<UUID, Integer> color) {
        var stack = new ArrayDeque<UUID>();
        var parent = new HashMap<UUID, UUID>();
        stack.push(start);
        color.put(start, 1);
        while (!stack.isEmpty()) {
            UUID node = stack.peek();
            boolean pushedChild = false;
            for (UUID next : outgoing.getOrDefault(node, List.of())) {
                int state = color.getOrDefault(next, 0);
                if (state == 0) {
                    color.put(next, 1);
                    parent.put(next, node);
                    stack.push(next);
                    pushedChild = true;
                    break;
                } else if (state == 1) {
                    return Optional.of(buildPath(parent, node, next));
                }
            }
            if (!pushedChild) {
                stack.pop();
                color.put(node, 2);
            }
        }
        return Optional.empty();
    }

    private static List<UUID> buildPath(Map<UUID, UUID> parent, UUID from, UUID backTo) {
        var reversed = new ArrayList<UUID>();
        for (UUID node = from; node != null; node = parent.get(node)) {
            reversed.add(node);
            if (node.equals(backTo)) break;
        }
        var path = new ArrayList<UUID>(reversed.reversed());
        path.add(backTo);
        return path;
    }
}
