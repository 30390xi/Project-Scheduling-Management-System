package com.company.scheduler.task.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DependencyGraphTest {

    /** AC-08：A→B→C 已存在，尝试 C→A 必须识别环路。 */
    @Test
    void detectsIndirectCycle() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        var edges = List.of(
                new DependencyGraph.Edge(a, b),
                new DependencyGraph.Edge(b, c),
                new DependencyGraph.Edge(c, a));
        var cycle = DependencyGraph.findCycle(edges);
        assertThat(cycle).isPresent();
        assertThat(cycle.get()).contains(a, b, c);
        assertThat(cycle.get().get(0)).isEqualTo(cycle.get().get(cycle.get().size() - 1));
    }

    @Test
    void acceptsAcyclicGraph() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        var edges = List.of(
                new DependencyGraph.Edge(a, b),
                new DependencyGraph.Edge(a, c),
                new DependencyGraph.Edge(b, d),
                new DependencyGraph.Edge(c, d));
        assertThat(DependencyGraph.findCycle(edges)).isEmpty();
    }

    @Test
    void detectsSelfLoop() {
        UUID a = UUID.randomUUID();
        assertThat(DependencyGraph.findCycle(List.of(new DependencyGraph.Edge(a, a)))).isPresent();
    }

    @Test
    void detectsCycleInDisconnectedComponent() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID x = UUID.randomUUID();
        UUID y = UUID.randomUUID();
        var edges = List.of(
                new DependencyGraph.Edge(a, b),
                new DependencyGraph.Edge(x, y),
                new DependencyGraph.Edge(y, x));
        assertThat(DependencyGraph.findCycle(edges)).isPresent();
    }

    @Test
    void emptyGraphHasNoCycle() {
        assertThat(DependencyGraph.findCycle(List.of())).isEmpty();
    }

    @Test
    void resultIsDeterministic() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        UUID c = UUID.fromString("00000000-0000-0000-0000-00000000000c");
        var edges = List.of(
                new DependencyGraph.Edge(b, c),
                new DependencyGraph.Edge(c, a),
                new DependencyGraph.Edge(a, b));
        assertThat(DependencyGraph.findCycle(edges)).isEqualTo(DependencyGraph.findCycle(edges));
    }
}
