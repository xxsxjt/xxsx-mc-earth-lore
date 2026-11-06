package com.xxsx.earthlore.core;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * All-or-nothing material allocation for research and compiling. Requirements may accept several
 * slots and slots may satisfy several requirements, so a greedy pass can fail where an assignment
 * exists; this solves it as a small max-flow problem.
 */
public final class Allocation {
    private Allocation() {
    }

    /**
     * @param slotCounts item count available in each slot
     * @param reqCounts  amount each requirement needs
     * @param accepts    accepts[r][s] is true when slot s can pay requirement r
     * @return take[r][s], or null when the slots cannot cover every requirement at once
     */
    public static int[][] solve(int[] slotCounts, int[] reqCounts, boolean[][] accepts) {
        int reqs = reqCounts.length;
        int slots = slotCounts.length;
        int nodes = reqs + slots + 2;
        int source = 0;
        int sink = nodes - 1;
        int[][] capacity = new int[nodes][nodes];
        int needed = 0;
        for (int r = 0; r < reqs; r++) {
            capacity[source][1 + r] = Math.max(0, reqCounts[r]);
            needed += Math.max(0, reqCounts[r]);
            for (int s = 0; s < slots; s++) {
                if (accepts[r][s]) {
                    capacity[1 + r][1 + reqs + s] = Integer.MAX_VALUE / 4;
                }
            }
        }
        for (int s = 0; s < slots; s++) {
            capacity[1 + reqs + s][sink] = Math.max(0, slotCounts[s]);
        }
        int[][] flow = new int[nodes][nodes];
        int total = 0;
        int[] parent = new int[nodes];
        while (true) {
            Arrays.fill(parent, -1);
            parent[source] = source;
            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(source);
            while (!queue.isEmpty() && parent[sink] == -1) {
                int u = queue.poll();
                for (int v = 0; v < nodes; v++) {
                    if (parent[v] == -1 && capacity[u][v] - flow[u][v] > 0) {
                        parent[v] = u;
                        queue.add(v);
                    }
                }
            }
            if (parent[sink] == -1) {
                break;
            }
            int push = Integer.MAX_VALUE;
            for (int v = sink; v != source; v = parent[v]) {
                push = Math.min(push, capacity[parent[v]][v] - flow[parent[v]][v]);
            }
            for (int v = sink; v != source; v = parent[v]) {
                flow[parent[v]][v] += push;
                flow[v][parent[v]] -= push;
            }
            total += push;
        }
        if (total < needed) {
            return null;
        }
        int[][] take = new int[reqs][slots];
        for (int r = 0; r < reqs; r++) {
            for (int s = 0; s < slots; s++) {
                take[r][s] = Math.max(0, flow[1 + r][1 + reqs + s]);
            }
        }
        return take;
    }
}

