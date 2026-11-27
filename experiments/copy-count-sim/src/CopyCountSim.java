import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * Copy-count simulator for nearby store-and-forward messaging.
 *
 * People move between popular places during the day and go home at night. Phones in
 * range link (up to LINK_CAP links each) and pass envelopes on under one of several
 * routing rules. Every rule runs on the same movement for a given seed. This models
 * the routing rules in docs/proposals/local-mesh-v3.1-group-envelopes.md, not Yap's
 * code: no radios, no Android, no encryption.
 */
public class CopyCountSim {

    // ---- fixed model settings -------------------------------------------------------
    static final double DT = 10;                       // seconds per step
    static final double RANGE = 40;                    // metres
    static final int LINK_CAP = 12;                    // links per phone (v2 slots)
    static final int LINK_BUDGET = 7;                  // envelopes per link per step (40 a minute)
    static final int PHONE_BUDGET = 17;                // envelopes a phone takes in per step (100 a minute)
    static final int MAX_HOPS = 8;
    static final double LIFETIME = 24 * 3600;
    static final double CREATE_SPAN = 24 * 3600;       // messages are made during the first day
    static final double DURATION = 48 * 3600;          // so every message lives its full 24 hours
    static final double DAY = 24 * 3600;
    static final double START_CLOCK = 8 * 3600;        // t = 0 is 08:00
    static final double NIGHT_FROM = 23 * 3600, NIGHT_TO = 7 * 3600;
    static final int INF = 1 << 28;

    /** mix: chance a trip goes to any place rather than a usual one. hubs: a few places are much busier. */
    record Scenario(String name, int phones, double size, int hotspots, double radius,
                    double pauseMin, double pauseMax, double mix, boolean hubs) {}

    static final List<Scenario> SCENARIOS = List.of(
        new Scenario("campus", 150, 800, 8, 40, 20, 120, 0.3, true),
        new Scenario("town", 400, 2500, 20, 50, 30, 180, 0.3, true),
        new Scenario("crowd", 800, 500, 5, 70, 5, 40, 0.3, true),
        new Scenario("sparse", 60, 3000, 10, 40, 30, 240, 0.3, true),
        new Scenario("district", 400, 4000, 40, 40, 30, 180, 0.05, false),
        new Scenario("scattered", 120, 4000, 30, 40, 30, 240, 0.05, false));

    enum Rule { SPRAY, EPIDEMIC, HANDON }

    record Policy(Rule rule, int copies) {
        String label() {
            return switch (rule) {
                case SPRAY -> "spray " + copies;
                case EPIDEMIC -> "unlimited";
                case HANDON -> "hand-on " + copies;
            };
        }
    }

    record Config(String name, Policy direct, Policy group) {}

    static final List<Config> CONFIGS = List.of(
        new Config("A", new Policy(Rule.SPRAY, 12), new Policy(Rule.SPRAY, 32)),
        new Config("B", new Policy(Rule.SPRAY, 24), new Policy(Rule.SPRAY, 64)),
        new Config("C", new Policy(Rule.SPRAY, 48), new Policy(Rule.SPRAY, 128)),
        new Config("D", new Policy(Rule.SPRAY, 96), new Policy(Rule.SPRAY, 255)),
        new Config("E", new Policy(Rule.EPIDEMIC, INF), new Policy(Rule.EPIDEMIC, INF)),
        new Config("F", new Policy(Rule.SPRAY, 12), new Policy(Rule.HANDON, 4)));

    static final int[] GROUP_SIZES = {5, 30, 128};
    static final int GROUPS_PER_SIZE = 3, MESSAGES_PER_GROUP = 10, JUNK = 10;

    // ---- movement ---------------------------------------------------------------------
    static final class World {
        final Scenario sc;
        final Random rnd;
        final int n;
        final double[] hx, hy, pop;
        final int[][] usual;
        final double[] x, y, tx, ty, speed, pauseUntil, homeX, homeY;
        final int[] dest;          // hotspot index, -1 = home, -2 = walking to a hotspot, -3 = walking home
        final int[] destHotspot;   // where a walker is heading
        final boolean[] walking;

        World(Scenario sc, long seed) {
            this.sc = sc;
            this.rnd = new Random(seed);
            n = sc.phones();
            hx = new double[sc.hotspots()];
            hy = new double[sc.hotspots()];
            pop = new double[sc.hotspots()];
            double total = 0;
            for (int h = 0; h < sc.hotspots(); h++) {
                hx[h] = sc.radius() + rnd.nextDouble() * (sc.size() - 2 * sc.radius());
                hy[h] = sc.radius() + rnd.nextDouble() * (sc.size() - 2 * sc.radius());
                pop[h] = sc.hubs() ? 1.0 / (h + 1) : 1.0;   // with hubs, a few places are much busier
                total += pop[h];
            }
            for (int h = 0; h < pop.length; h++) pop[h] /= total;
            usual = new int[n][];
            x = new double[n]; y = new double[n]; tx = new double[n]; ty = new double[n];
            speed = new double[n]; pauseUntil = new double[n];
            homeX = new double[n]; homeY = new double[n];
            dest = new int[n]; destHotspot = new int[n]; walking = new boolean[n];
            for (int p = 0; p < n; p++) {
                int k = Math.min(3, sc.hotspots());
                homeX[p] = rnd.nextDouble() * sc.size();
                homeY[p] = rnd.nextDouble() * sc.size();
                if (sc.hubs()) {
                    LinkedHashSet<Integer> set = new LinkedHashSet<>();
                    while (set.size() < k) set.add(pickByPopularity());
                    usual[p] = set.stream().mapToInt(Integer::intValue).toArray();
                } else {                                   // without hubs, people use the places nearest home
                    final int q = p;
                    usual[p] = IntStream.range(0, sc.hotspots()).boxed()
                        .sorted(Comparator.comparingDouble(h -> Math.hypot(hx[h] - homeX[q], hy[h] - homeY[q])))
                        .limit(k).mapToInt(Integer::intValue).toArray();
                }
                speed[p] = 0.8 + rnd.nextDouble() * 0.7;
                int h = usual[p][rnd.nextInt(usual[p].length)];
                placeIn(p, h);
                dest[p] = h;
                pauseUntil[p] = rnd.nextDouble() * sc.pauseMax() * 60;
            }
        }

        int pickByPopularity() {
            double r = rnd.nextDouble();
            for (int h = 0; h < pop.length; h++) { r -= pop[h]; if (r <= 0) return h; }
            return pop.length - 1;
        }

        void placeIn(int p, int h) {
            double a = rnd.nextDouble() * 2 * Math.PI, d = sc.radius() * Math.sqrt(rnd.nextDouble());
            x[p] = hx[h] + d * Math.cos(a);
            y[p] = hy[h] + d * Math.sin(a);
        }

        static boolean night(double t) {
            double c = (t + START_CLOCK) % DAY;
            return c >= NIGHT_FROM || c < NIGHT_TO;
        }

        void step(double t) {
            boolean night = night(t);
            for (int p = 0; p < n; p++) {
                if (walking[p]) {
                    double dx = tx[p] - x[p], dy = ty[p] - y[p], d = Math.hypot(dx, dy), s = speed[p] * DT;
                    if (d <= s) {
                        x[p] = tx[p]; y[p] = ty[p]; walking[p] = false;
                        if (dest[p] == -3) { dest[p] = -1; }
                        else {
                            dest[p] = destHotspot[p];
                            pauseUntil[p] = t + (sc.pauseMin() + rnd.nextDouble() * (sc.pauseMax() - sc.pauseMin())) * 60;
                        }
                    } else { x[p] += dx / d * s; y[p] += dy / d * s; }
                    continue;
                }
                if (night) {
                    if (dest[p] != -1) {                       // go home for the night
                        tx[p] = homeX[p]; ty[p] = homeY[p]; walking[p] = true; dest[p] = -3;
                    }
                    continue;
                }
                if (dest[p] == -1) {                           // morning: leave home at a random time
                    if (rnd.nextDouble() < DT / 3600.0) goTo(p, nextHotspot(p));
                    continue;
                }
                if (t >= pauseUntil[p]) goTo(p, nextHotspot(p));
                else if (rnd.nextDouble() < DT / 300.0) placeIn(p, dest[p]);   // move about within the place
            }
        }

        int nextHotspot(int p) {
            return rnd.nextDouble() >= sc.mix() ? usual[p][rnd.nextInt(usual[p].length)] : pickByPopularity();
        }

        void goTo(int p, int h) {
            double a = rnd.nextDouble() * 2 * Math.PI, d = sc.radius() * Math.sqrt(rnd.nextDouble());
            tx[p] = hx[h] + d * Math.cos(a);
            ty[p] = hy[h] + d * Math.sin(a);
            destHotspot[p] = h;
            dest[p] = -2;
            walking[p] = true;
        }
    }

    // ---- messages ---------------------------------------------------------------------
    static final class Msg {
        final int id, sender, recipient;      // recipient -1 for group and junk messages
        final boolean group, junk;
        final int groupSize;
        final boolean[] member;
        final double created;
        final Policy policy;
        final int[] copies;                   // 0 = not holding
        final byte[] hops;
        final boolean[] has;                  // holds it now, or had it, or is a reached recipient
        final int[] handOns;
        final double[] reachedAt;
        int strangers, transmissions, reached;
        boolean alive = false, expired = false;

        Msg(int id, int n, int sender, int recipient, boolean group, boolean junk, int groupSize,
            boolean[] member, double created, Policy policy) {
            this.id = id; this.sender = sender; this.recipient = recipient; this.group = group;
            this.junk = junk; this.groupSize = groupSize; this.member = member; this.created = created;
            this.policy = policy;
            copies = new int[n]; hops = new byte[n]; has = new boolean[n]; handOns = new int[n];
            reachedAt = new double[n];
            Arrays.fill(reachedAt, -1);
        }

        boolean isMember(int p) { return member != null && member[p]; }
        boolean carrierStore(int p) { return p != sender; }
    }

    static final class Run {
        final Scenario sc; final Config cfg; final long seed;
        final World world;
        final int n;
        final List<Msg> msgs = new ArrayList<>();
        final List<List<Msg>> held;
        final int[] heldCount, peakHeld;
        final Map<Long, int[]> links = new HashMap<>();   // key -> {a, b, dirty}
        final int[] linkCount;
        final List<List<Long>> linksOf;

        Run(Scenario sc, Config cfg, long seed) {
            this.sc = sc; this.cfg = cfg; this.seed = seed;
            world = new World(sc, seed);
            n = sc.phones();
            held = new ArrayList<>();
            for (int p = 0; p < n; p++) held.add(new ArrayList<>());
            heldCount = new int[n]; peakHeld = new int[n];
            linkCount = new int[n];
            linksOf = new ArrayList<>();
            for (int p = 0; p < n; p++) linksOf.add(new ArrayList<>());
            makeMessages(new Random(seed * 7919 + 17));
        }

        void makeMessages(Random r) {
            int id = 0;
            for (int i = 0; i < n; i++) {                         // one one-to-one message per phone per day
                int s = r.nextInt(n), t;
                if (r.nextDouble() < 0.5) t = friendOf(s, r); else do t = r.nextInt(n); while (t == s);
                msgs.add(new Msg(id++, n, s, t, false, false, 0, null, r.nextDouble() * CREATE_SPAN, cfg.direct()));
            }
            for (int g : GROUP_SIZES) {
                if (g > n - 1) continue;
                for (int k = 0; k < GROUPS_PER_SIZE; k++) {
                    boolean[] member = pickGroup(g, r);
                    int[] ids = IntStream.range(0, n).filter(p -> member[p]).toArray();
                    for (int m = 0; m < MESSAGES_PER_GROUP; m++) {
                        int s = ids[r.nextInt(ids.length)];
                        msgs.add(new Msg(id++, n, s, -1, true, false, g, member, r.nextDouble() * CREATE_SPAN, cfg.group()));
                    }
                }
            }
            for (int j = 0; j < JUNK; j++) {                      // a group label nobody recognises
                msgs.add(new Msg(id++, n, r.nextInt(n), -1, true, true, 0, new boolean[n], r.nextDouble() * CREATE_SPAN, cfg.group()));
            }
            msgs.sort(Comparator.comparingDouble(m -> m.created));
        }

        int friendOf(int s, Random r) {
            List<Integer> c = new ArrayList<>();
            for (int p = 0; p < n; p++) if (p != s && shares(s, p)) c.add(p);
            if (c.isEmpty()) { int t; do t = r.nextInt(n); while (t == s); return t; }
            return c.get(r.nextInt(c.size()));
        }

        boolean shares(int a, int b) {
            for (int h : world.usual[a]) for (int k : world.usual[b]) if (h == k) return true;
            return false;
        }

        boolean[] pickGroup(int g, Random r) {
            int base = world.usual[r.nextInt(n)][0];
            List<Integer> local = new ArrayList<>(), rest = new ArrayList<>();
            for (int p = 0; p < n; p++) {
                boolean has = false;
                for (int h : world.usual[p]) if (h == base) has = true;
                (has ? local : rest).add(p);
            }
            Collections.shuffle(local, r); Collections.shuffle(rest, r);
            boolean[] m = new boolean[n];
            int count = 0, li = 0, ri = 0;
            while (count < g) {
                boolean fromLocal = li < local.size() && (r.nextDouble() < 0.7 || ri >= rest.size());
                int p = fromLocal ? local.get(li++) : rest.get(ri++);
                if (!m[p]) { m[p] = true; count++; }
            }
            return m;
        }

        static long key(int a, int b) { return a < b ? ((long) a << 32) | b : ((long) b << 32) | a; }

        void hold(Msg m, int p, int copies, int hops) {
            if (m.copies[p] == 0) {
                held.get(p).add(m);
                if (m.carrierStore(p)) {
                    heldCount[p]++;
                    if (heldCount[p] > peakHeld[p]) peakHeld[p] = heldCount[p];
                }
            }
            m.copies[p] = copies;
            m.hops[p] = (byte) hops;
            m.has[p] = true;
        }

        void drop(Msg m, int p) {
            if (m.copies[p] == 0) return;
            m.copies[p] = 0;
            held.get(p).remove(m);
            if (m.carrierStore(p)) heldCount[p]--;
        }

        void dirtyLinksOf(int p) {
            for (long k : linksOf.get(p)) { int[] l = links.get(k); if (l != null) l[2] = 1; }
        }

        void reach(Msg m, int p, double t) {
            if (m.reachedAt[p] < 0) { m.reachedAt[p] = t; m.reached++; }
        }

        /** Tries to pass m from a to b. Returns 1 if sent, 0 if the rule says no, -1 if out of budget. */
        int offer(Msg m, int a, int b, double t, int[] linkUsed, int[] recv, int dir) {
            if (!m.alive || m.copies[a] == 0) return 0;
            if (m.has[b]) {
                // b already has it. A one-to-one recipient answers DELIVERED and the carrier deletes.
                if (!m.group && b == m.recipient && a != m.sender) drop(m, a);
                return 0;
            }
            if (m.hops[a] >= MAX_HOPS) return 0;
            int give;
            boolean keep;            // whether a keeps its copy count unchanged
            Policy pol = m.policy;
            if (!m.group) {
                if (b == m.recipient) { give = -1; keep = false; }
                else if (pol.rule() == Rule.EPIDEMIC) { give = INF; keep = true; }
                else if (m.copies[a] > 1) { give = m.copies[a] / 2; keep = false; }
                else return 0;
            } else {
                switch (pol.rule()) {
                    case EPIDEMIC -> { give = INF; keep = true; }
                    case HANDON -> {
                        if (m.handOns[a] >= pol.copies()) return 0;
                        give = 1; keep = true;
                    }
                    default -> {
                        if (m.copies[a] > 1) { give = m.copies[a] / 2; keep = false; }
                        else if (m.isMember(b)) { give = 1; keep = true; }
                        else return 0;
                    }
                }
            }
            if (linkUsed[dir] >= LINK_BUDGET || recv[b] >= PHONE_BUDGET) return -1;
            linkUsed[dir]++; recv[b]++;
            m.transmissions++;
            if (!m.group && b == m.recipient) {
                m.has[b] = true;
                reach(m, b, t);
                if (a != m.sender) drop(m, a);
                // The receipt is modelled as instant: the sender stops offering.
                drop(m, m.sender);
                return 1;
            }
            if (!keep) m.copies[a] -= give;
            if (pol.rule() == Rule.HANDON) m.handOns[a]++;
            hold(m, b, give, m.hops[a] + 1);
            if (!m.isMember(b) && b != m.recipient) m.strangers++;
            if (m.isMember(b)) reach(m, b, t);
            dirtyLinksOf(b);
            return 1;
        }

        void exchange(int[] l, double t, int[] recv) {
            int a = l[0], b = l[1];
            int[] used = new int[2];
            boolean blocked = false;
            for (Msg m : new ArrayList<>(held.get(a))) if (offer(m, a, b, t, used, recv, 0) < 0) blocked = true;
            for (Msg m : new ArrayList<>(held.get(b))) if (offer(m, b, a, t, used, recv, 1) < 0) blocked = true;
            l[2] = blocked ? 1 : 0;
        }

        void updateLinks() {
            double cell = RANGE;
            int cells = (int) Math.ceil(sc.size() / cell) + 1;
            Map<Integer, List<Integer>> grid = new HashMap<>();
            for (int p = 0; p < n; p++) {
                int cx = clamp((int) (world.x[p] / cell), cells), cy = clamp((int) (world.y[p] / cell), cells);
                grid.computeIfAbsent(cx * cells + cy, k -> new ArrayList<>()).add(p);
            }
            HashSet<Long> inRange = new HashSet<>();
            List<long[]> candidates = new ArrayList<>();
            for (int p = 0; p < n; p++) {
                int cx = clamp((int) (world.x[p] / cell), cells), cy = clamp((int) (world.y[p] / cell), cells);
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
                    List<Integer> c = grid.get((cx + dx) * cells + (cy + dy));
                    if (c == null) continue;
                    for (int q : c) {
                        if (q <= p) continue;
                        double d = Math.hypot(world.x[p] - world.x[q], world.y[p] - world.y[q]);
                        if (d <= RANGE) { long k = key(p, q); inRange.add(k); candidates.add(new long[]{k, (long) (d * 1000)}); }
                    }
                }
            }
            for (Iterator<Map.Entry<Long, int[]>> it = links.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Long, int[]> e = it.next();
                if (!inRange.contains(e.getKey())) {
                    int[] l = e.getValue();
                    linkCount[l[0]]--; linkCount[l[1]]--;
                    linksOf.get(l[0]).remove(e.getKey()); linksOf.get(l[1]).remove(e.getKey());
                    it.remove();
                }
            }
            candidates.sort(Comparator.comparingLong(c -> c[1]));    // nearest first
            for (long[] c : candidates) {
                long k = c[0];
                if (links.containsKey(k)) continue;
                int a = (int) (k >>> 32), b = (int) (k & 0xffffffffL);
                if (linkCount[a] >= LINK_CAP || linkCount[b] >= LINK_CAP) continue;
                links.put(k, new int[]{a, b, 1});
                linkCount[a]++; linkCount[b]++;
                linksOf.get(a).add(k); linksOf.get(b).add(k);
            }
        }

        static int clamp(int v, int max) { return Math.max(0, Math.min(max - 1, v)); }

        Result run() {
            int next = 0;
            List<Msg> live = new ArrayList<>();
            for (double t = 0; t < DURATION; t += DT) {
                world.step(t);
                updateLinks();
                while (next < msgs.size() && msgs.get(next).created <= t) {
                    Msg m = msgs.get(next++);
                    m.alive = true;
                    hold(m, m.sender, m.policy.copies(), 0);
                    if (m.isMember(m.sender)) m.reachedAt[m.sender] = t;   // the sender is not counted as reached
                    dirtyLinksOf(m.sender);
                    live.add(m);
                }
                int[] recv = new int[n];
                List<int[]> dirty = new ArrayList<>();
                for (int[] l : links.values()) if (l[2] == 1) dirty.add(l);
                Collections.shuffle(dirty, new Random((long) t ^ seed));
                for (int[] l : dirty) exchange(l, t, recv);
                for (Iterator<Msg> it = live.iterator(); it.hasNext(); ) {
                    Msg m = it.next();
                    if (t - m.created >= LIFETIME) {
                        for (int p = 0; p < n; p++) drop(m, p);
                        m.alive = false; m.expired = true;
                        it.remove();
                    }
                }
            }
            return new Result(sc, cfg, seed, msgs, peakHeld);
        }
    }

    record Result(Scenario sc, Config cfg, long seed, List<Msg> msgs, int[] peakHeld) {}

    // ---- reporting --------------------------------------------------------------------
    record Row(String scenario, String config, String kind, String policy, long seed,
               double delivered, double allReached, double medianDelayH, double p90DelayH,
               double strangers, double transmissions, int peakStore, double meanPeakStore) {}

    static List<Row> rows(Result r) {
        List<Row> out = new ArrayList<>();
        int peak = Arrays.stream(r.peakHeld()).max().orElse(0);
        double meanPeak = Arrays.stream(r.peakHeld()).average().orElse(0);
        // one-to-one
        List<Msg> direct = r.msgs().stream().filter(m -> !m.group).toList();
        out.add(summarise(r, "one-to-one", r.cfg().direct().label(), direct, peak, meanPeak));
        for (int g : GROUP_SIZES) {
            List<Msg> gm = r.msgs().stream().filter(m -> m.group && !m.junk && m.groupSize == g).toList();
            if (!gm.isEmpty()) out.add(summarise(r, "group " + g, r.cfg().group().label(), gm, peak, meanPeak));
        }
        List<Msg> junk = r.msgs().stream().filter(m -> m.junk).toList();
        out.add(summarise(r, "junk", r.cfg().group().label(), junk, peak, meanPeak));
        return out;
    }

    static Row summarise(Result r, String kind, String policy, List<Msg> ms, int peak, double meanPeak) {
        double delivered = 0, all = 0;
        List<Double> delays = new ArrayList<>();
        double strangers = 0, tx = 0;
        for (Msg m : ms) {
            int wanted = m.group ? (m.junk ? 0 : m.groupSize - 1) : 1;
            int got = 0;
            for (int p = 0; p < m.reachedAt.length; p++) {
                if (m.reachedAt[p] < 0 || p == m.sender) continue;
                if (m.group ? m.member[p] : p == m.recipient) { got++; delays.add((m.reachedAt[p] - m.created) / 3600); }
            }
            if (wanted > 0) { delivered += (double) got / wanted; if (got == wanted) all++; }
            strangers += m.strangers;
            tx += m.transmissions;
        }
        Collections.sort(delays);
        int k = ms.size();
        return new Row(r.sc().name(), r.cfg().name(), kind, policy, r.seed(),
            100 * delivered / k, 100 * all / k,
            delays.isEmpty() ? Double.NaN : delays.get(delays.size() / 2),
            delays.isEmpty() ? Double.NaN : delays.get((int) (delays.size() * 0.9)),
            strangers / k, tx / k, peak, meanPeak);
    }

    public static void main(String[] args) throws Exception {
        int seeds = 5;
        String out = "results";
        String only = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seeds" -> seeds = Integer.parseInt(args[++i]);
                case "--out" -> out = args[++i];
                case "--only" -> only = args[++i];
                case "--quick" -> seeds = 1;
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        List<Callable<Result>> jobs = new ArrayList<>();
        for (Scenario sc : SCENARIOS) {
            if (only != null && !only.equals(sc.name())) continue;
            for (int s = 1; s <= seeds; s++) for (Config c : CONFIGS) {
                long seed = s * 1000L + sc.name().hashCode();
                jobs.add(() -> new Run(sc, c, seed).run());
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        List<Row> all = new ArrayList<>();
        int done = 0;
        for (Future<Result> f : pool.invokeAll(jobs)) {
            all.addAll(rows(f.get()));
            System.err.printf("%d/%d runs%n", ++done, jobs.size());
        }
        pool.shutdown();
        Files.createDirectories(Path.of(out));
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(out, "results.csv")))) {
            w.println("scenario,config,kind,policy,seed,delivered_pct,all_reached_pct,median_delay_h,p90_delay_h,strangers,transmissions,peak_store,mean_peak_store");
            for (Row r : all) w.printf(Locale.ROOT, "%s,%s,%s,%s,%d,%.2f,%.2f,%.3f,%.3f,%.2f,%.2f,%d,%.2f%n",
                r.scenario(), r.config(), r.kind(), r.policy(), r.seed(), r.delivered(), r.allReached(),
                r.medianDelayH(), r.p90DelayH(), r.strangers(), r.transmissions(), r.peakStore(), r.meanPeakStore());
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(out, "summary.md")))) {
            w.printf("# Copy-count simulation results%n%n%d seeds per scenario. Mean across seeds, with the spread (one standard deviation) after ±.%n", seeds);
            w.println("Every number is a model output, not a measurement on phones.");
            Map<String, Map<String, List<Row>>> by = new LinkedHashMap<>();
            for (Row r : all) by.computeIfAbsent(r.scenario(), k -> new LinkedHashMap<>())
                .computeIfAbsent(r.kind(), k -> new ArrayList<>()).add(r);
            for (var sc : by.entrySet()) {
                w.printf("%n## %s%n", sc.getKey());
                for (var kind : sc.getValue().entrySet()) {
                    w.printf("%n### %s%n%n", kind.getKey());
                    boolean junk = kind.getKey().equals("junk");
                    w.println(junk ? "| Rule | Strangers carrying each message | Sends per message |"
                                   : "| Rule | Reached | All reached | Median delay (h) | 90% within (h) | Strangers carrying | Sends per message |");
                    w.println(junk ? "| --- | --- | --- |" : "| --- | --- | --- | --- | --- | --- | --- |");
                    Map<String, List<Row>> byPol = new LinkedHashMap<>();
                    for (Row r : kind.getValue()) byPol.computeIfAbsent(r.config() + " " + r.policy(), k -> new ArrayList<>()).add(r);
                    for (var p : byPol.entrySet()) {
                        List<Row> rs = p.getValue();
                        String label = p.getKey().substring(2);
                        if (junk) w.printf(Locale.ROOT, "| %s | %s | %s |%n", label,
                            ms(rs, Row::strangers, 0), ms(rs, Row::transmissions, 0));
                        else w.printf(Locale.ROOT, "| %s | %s%% | %s%% | %s | %s | %s | %s |%n", label,
                            ms(rs, Row::delivered, 1), ms(rs, Row::allReached, 0), ms(rs, Row::medianDelayH, 1),
                            ms(rs, Row::p90DelayH, 1), ms(rs, Row::strangers, 0), ms(rs, Row::transmissions, 0));
                    }
                }
                w.printf("%n### Carrier storage (most envelopes held by one phone at once)%n%n| Config | Busiest phone | Average phone |%n| --- | --- | --- |%n");
                Map<String, List<Row>> byCfg = new LinkedHashMap<>();
                for (Row r : sc.getValue().get("one-to-one")) byCfg.computeIfAbsent(r.config(), k -> new ArrayList<>()).add(r);
                for (var c : byCfg.entrySet()) {
                    Config cfg = CONFIGS.stream().filter(x -> x.name().equals(c.getKey())).findFirst().orElseThrow();
                    w.printf(Locale.ROOT, "| %s / %s | %s | %s |%n", cfg.direct().label(), cfg.group().label(),
                        ms(c.getValue(), r -> r.peakStore(), 0), ms(c.getValue(), Row::meanPeakStore, 0));
                }
            }
        }
        System.err.println("wrote " + out);
    }

    interface F { double get(Row r); }

    static String ms(List<Row> rs, F f, int dp) {
        double[] v = rs.stream().mapToDouble(f::get).filter(d -> !Double.isNaN(d)).toArray();
        if (v.length == 0) return "–";
        double mean = Arrays.stream(v).average().orElse(0);
        double sd = Math.sqrt(Arrays.stream(v).map(d -> (d - mean) * (d - mean)).sum() / Math.max(1, v.length - 1));
        String fmt = "%." + dp + "f";
        return String.format(Locale.ROOT, fmt + " ± " + fmt, mean, sd);
    }
}
