package dev.navigationai.runtime;

import dev.navigationai.navigation.GridPos;
import java.util.*;

/** Map from grid cells to values, stored in a flat array over a {@link CellSet.CellBox}. */
final class CellMap<V> extends AbstractMap<GridPos, V> {
    private final CellSet.CellBox box;
    private final Object[] values;
    private final Map<GridPos, V> overflow = new HashMap<>();
    private int size;

    CellMap(CellSet.CellBox box) { this.box = box; this.values = new Object[box.volume()]; }

    @SuppressWarnings("unchecked")
    V get(int x, int y, int z) {
        int i = box.index(x, y, z);
        return i >= 0 ? (V) values[i] : overflow.isEmpty() ? null : overflow.get(new GridPos(x, y, z));
    }
    @Override public V get(Object key) { return key instanceof GridPos p ? get(p.x(), p.y(), p.z()) : null; }
    @Override public V getOrDefault(Object key, V fallback) { V value = get(key); return value == null ? fallback : value; }
    @Override public boolean containsKey(Object key) { return get(key) != null; }
    @Override @SuppressWarnings("unchecked") public V put(GridPos key, V value) {
        Objects.requireNonNull(value);
        int i = box.index(key.x(), key.y(), key.z());
        if (i < 0) { V old = overflow.put(key, value); if (old == null) size++; return old; }
        V old = (V) values[i];
        values[i] = value;
        if (old == null) size++;
        return old;
    }
    @Override @SuppressWarnings("unchecked") public V remove(Object key) {
        if (!(key instanceof GridPos p)) return null;
        int i = box.index(p.x(), p.y(), p.z());
        if (i < 0) { V old = overflow.remove(p); if (old != null) size--; return old; }
        V old = (V) values[i];
        values[i] = null;
        if (old != null) size--;
        return old;
    }
    @Override public int size() { return size; }
    @Override public Set<Entry<GridPos, V>> entrySet() {
        return new AbstractSet<>() {
            @Override public int size() { return size; }
            @Override @SuppressWarnings("unchecked") public Iterator<Entry<GridPos, V>> iterator() {
                List<Entry<GridPos, V>> all = new ArrayList<>(size);
                for (int i = 0; i < values.length; i++) if (values[i] != null) all.add(new SimpleImmutableEntry<>(box.cell(i), (V) values[i]));
                all.addAll(overflow.entrySet());
                return Collections.unmodifiableList(all).iterator();
            }
        };
    }
}
