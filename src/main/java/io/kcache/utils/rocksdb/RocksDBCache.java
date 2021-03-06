/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kcache.utils.rocksdb;

import com.google.common.primitives.SignedBytes;
import io.kcache.Cache;
import io.kcache.KeyValueIterator;
import io.kcache.KeyValueIterators;
import io.kcache.exceptions.CacheException;
import io.kcache.exceptions.CacheInitializationException;
import io.kcache.utils.Streams;
import org.apache.kafka.common.serialization.Serde;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompactionStyle;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.FlushOptions;
import org.rocksdb.InfoLogLevel;
import org.rocksdb.LRUCache;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A persistent key-value store based on RocksDB.
 */
public class RocksDBCache<K, V> implements Cache<K, V> {
    private static final Logger log = LoggerFactory.getLogger(RocksDBCache.class);

    private static final Comparator<byte[]> BYTES_COMPARATOR = SignedBytes.lexicographicalComparator();

    private static final CompressionType COMPRESSION_TYPE = CompressionType.NO_COMPRESSION;
    private static final CompactionStyle COMPACTION_STYLE = CompactionStyle.UNIVERSAL;
    private static final long WRITE_BUFFER_SIZE = 16 * 1024 * 1024L;
    private static final long BLOCK_CACHE_SIZE = 50 * 1024 * 1024L;
    private static final long BLOCK_SIZE = 4096L;
    private static final int MAX_WRITE_BUFFERS = 3;
    private static final String DB_FILE_DIR = "rocksdb";

    private final String name;
    private final String parentDir;
    private final String rootDir;
    private final Serde<K> keySerde;
    private final Serde<V> valueSerde;
    final Comparator<K> comparator;
    private final Set<KeyValueIterator<byte[], byte[]>> openIterators = Collections.synchronizedSet(new HashSet<>());

    private File dbDir;
    private RocksDB db;
    private RocksDBAccessor dbAccessor;

    // the following option objects will be created in openDB and closed in the close() method
    private RocksDBGenericOptionsToDbOptionsColumnFamilyOptionsAdapter userSpecifiedOptions;
    private WriteOptions wOptions;
    private FlushOptions fOptions;
    private org.rocksdb.Cache cache;
    private BloomFilter filter;

    private volatile boolean open = false;

    public RocksDBCache(final String name,
                        final String rootDir,
                        Serde<K> keySerde,
                        Serde<V> valueSerde) {
        this(name, DB_FILE_DIR, rootDir, keySerde, valueSerde);
    }

    public RocksDBCache(final String name,
                        final String parentDir,
                        final String rootDir,
                        Serde<K> keySerde,
                        Serde<V> valueSerde) {
        this(name, parentDir, rootDir, keySerde, valueSerde, null);
    }

    public RocksDBCache(final String name,
                        final String parentDir,
                        final String rootDir,
                        Serde<K> keySerde,
                        Serde<V> valueSerde,
                        Comparator<K> comparator) {
        this.name = name;
        this.parentDir = parentDir;
        this.rootDir = rootDir;
        this.keySerde = keySerde;
        this.valueSerde = valueSerde;
        this.comparator = comparator != null ? comparator : (k1, k2) -> {
            byte[] b1 = keySerde.serializer().serialize(null, k1);
            byte[] b2 = keySerde.serializer().serialize(null, k2);
            return BYTES_COMPARATOR.compare(b1, b2);
        };
    }

    private void openDB() {
        // initialize the default rocksdb options

        final DBOptions dbOptions = new DBOptions();
        final ColumnFamilyOptions columnFamilyOptions = new ColumnFamilyOptions();
        userSpecifiedOptions = new RocksDBGenericOptionsToDbOptionsColumnFamilyOptionsAdapter(dbOptions, columnFamilyOptions);
        userSpecifiedOptions.setComparator(new RocksDBKeySliceComparator<>(keySerde, comparator));

        final BlockBasedTableConfig tableConfig = new BlockBasedTableConfig();
        cache = new LRUCache(BLOCK_CACHE_SIZE);
        tableConfig.setBlockCache(cache);
        tableConfig.setBlockSize(BLOCK_SIZE);

        filter = new BloomFilter();
        tableConfig.setFilterPolicy(filter);

        userSpecifiedOptions.optimizeFiltersForHits();
        userSpecifiedOptions.setTableFormatConfig(tableConfig);
        userSpecifiedOptions.setWriteBufferSize(WRITE_BUFFER_SIZE);
        userSpecifiedOptions.setCompressionType(COMPRESSION_TYPE);
        userSpecifiedOptions.setCompactionStyle(COMPACTION_STYLE);
        userSpecifiedOptions.setMaxWriteBufferNumber(MAX_WRITE_BUFFERS);
        userSpecifiedOptions.setCreateIfMissing(true);
        userSpecifiedOptions.setErrorIfExists(false);
        userSpecifiedOptions.setInfoLogLevel(InfoLogLevel.ERROR_LEVEL);
        // this is the recommended way to increase parallelism in RocksDb
        // note that the current implementation of setIncreaseParallelism affects the number
        // of compaction threads but not flush threads (the latter remains one). Also
        // the parallelism value needs to be at least two because of the code in
        // https://github.com/facebook/rocksdb/blob/62ad0a9b19f0be4cefa70b6b32876e764b7f3c11/util/options.cc#L580
        // subtracts one from the value passed to determine the number of compaction threads
        // (this could be a bug in the RocksDB code and their devs have been contacted).
        userSpecifiedOptions.setIncreaseParallelism(Math.max(Runtime.getRuntime().availableProcessors(), 2));

        wOptions = new WriteOptions();
        wOptions.setDisableWAL(true);

        fOptions = new FlushOptions();
        fOptions.setWaitForFlush(true);

        dbDir = new File(new File(rootDir, parentDir), name);

        try {
            Files.createDirectories(dbDir.getParentFile().toPath());
            Files.createDirectories(dbDir.getAbsoluteFile().toPath());
        } catch (final IOException fatal) {
            throw new CacheInitializationException("Could not create directories", fatal);
        }

        openRocksDB(dbOptions, columnFamilyOptions);
        open = true;
    }

    private void openRocksDB(final DBOptions dbOptions,
                             final ColumnFamilyOptions columnFamilyOptions) {
        final List<ColumnFamilyDescriptor> columnFamilyDescriptors
            = Collections.singletonList(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, columnFamilyOptions));
        final List<ColumnFamilyHandle> columnFamilies = new ArrayList<>(columnFamilyDescriptors.size());

        try {
            db = RocksDB.open(dbOptions, dbDir.getAbsolutePath(), columnFamilyDescriptors, columnFamilies);
            dbAccessor = new SingleColumnFamilyAccessor(columnFamilies.get(0));
        } catch (final RocksDBException e) {
            throw new CacheInitializationException("Error opening store " + name + " at location " + dbDir.toString(), e);
        }
    }

    @Override
    public synchronized void init() {
        // open the DB dir
        openDB();
    }

    @Override
    public void sync() {
        // do nothing
    }

    private void validateStoreOpen() {
        if (!open) {
            throw new CacheException("Store " + name + " is currently closed");
        }
    }

    @Override
    public int size() {
        validateStoreOpen();
        return (int) approximateNumEntries();
    }

    @Override
    public boolean isEmpty() {
        validateStoreOpen();
        return size() == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        validateStoreOpen();
        return get(key) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public V put(final K key, final V value) {
        Objects.requireNonNull(key, "key cannot be null");
        validateStoreOpen();
        final V originalValue = get(key);
        byte[] keyBytes = keySerde.serializer().serialize(null, key);
        byte[] valueBytes = valueSerde.serializer().serialize(null, value);
        dbAccessor.put(keyBytes, valueBytes);
        return originalValue;
    }

    @Override
    public V putIfAbsent(final K key, final V value) {
        // Threads accessing this method should use external synchronization
        // See https://github.com/facebook/rocksdb/issues/433
        Objects.requireNonNull(key, "key cannot be null");
        final V originalValue = get(key);
        if (originalValue == null) {
            put(key, value);
        }
        return originalValue;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> entries) {
        validateStoreOpen();
        try (final WriteBatch batch = new WriteBatch()) {
            Map<byte[], byte[]> rawEntries = entries.entrySet().stream()
                .collect(Collectors.toMap(
                    e -> keySerde.serializer().serialize(null, e.getKey()),
                    e -> valueSerde.serializer().serialize(null, e.getValue())));
            dbAccessor.prepareBatch(rawEntries, batch);
            write(batch);
        } catch (final RocksDBException e) {
            throw new CacheException("Error while batch writing to store " + name, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(final Object key) {
        validateStoreOpen();
        try {
            byte[] keyBytes = keySerde.serializer().serialize(null, (K) key);
            byte[] valueBytes = dbAccessor.get(keyBytes);
            return valueSerde.deserializer().deserialize(null, valueBytes);
        } catch (final RocksDBException e) {
            // String format is happening in wrapping stores. So formatted message is thrown from wrapping stores.
            throw new CacheException("Error while getting value for key from store " + name, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public V remove(final Object key) {
        Objects.requireNonNull(key, "key cannot be null");
        return put((K) key, null);
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<K> keySet() {
        return Streams.streamOf(all())
            .map(kv -> kv.key)
            .collect(Collectors.toSet());
    }

    @Override
    public Collection<V> values() {
        return Streams.streamOf(all())
            .map(kv -> kv.value)
            .collect(Collectors.toList());
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        return Streams.streamOf(all())
            .map(kv -> new AbstractMap.SimpleEntry<>(kv.key, kv.value))
            .collect(Collectors.toSet());
    }

    @Override
    public Cache<K, V> subCache(K from, boolean fromInclusive, K to, boolean toInclusive) {
        return new SubCache<>(this, from, fromInclusive, to, toInclusive, false);
    }

    @Override
    public KeyValueIterator<K, V> range(K from, boolean fromInclusive, K to, boolean toInclusive) {
        byte[] fromBytes = keySerde.serializer().serialize(null, from);
        byte[] toBytes = keySerde.serializer().serialize(null, to);

        validateStoreOpen();

        final KeyValueIterator<byte[], byte[]> rocksDBIterator = dbAccessor.range(fromBytes, fromInclusive, toBytes, toInclusive);
        openIterators.add(rocksDBIterator);

        return KeyValueIterators.transformRawIterator(keySerde, valueSerde, rocksDBIterator);
    }

    @Override
    public KeyValueIterator<K, V> all() {
        validateStoreOpen();
        final KeyValueIterator<byte[], byte[]> rocksDBIterator = dbAccessor.all();
        openIterators.add(rocksDBIterator);
        return KeyValueIterators.transformRawIterator(keySerde, valueSerde, rocksDBIterator);
    }

    /**
     * Return an approximate count of key-value mappings in this store.
     *
     * <code>RocksDB</code> cannot return an exact entry count without doing a
     * full scan, so this method relies on the <code>rocksdb.estimate-num-keys</code>
     * property to get an approximate count. The returned size also includes
     * a count of dirty keys in the store's in-memory cache, which may lead to some
     * double-counting of entries and inflate the estimate.
     *
     * @return an approximate count of key-value mappings in the store.
     */
    private long approximateNumEntries() {
        validateStoreOpen();
        final long numEntries;
        try {
            numEntries = dbAccessor.approximateNumEntries();
        } catch (final RocksDBException e) {
            throw new CacheException("Error fetching property from store " + name, e);
        }
        if (isOverflowing(numEntries)) {
            return Long.MAX_VALUE;
        }
        return numEntries;
    }

    private boolean isOverflowing(final long value) {
        // RocksDB returns an unsigned 8-byte integer, which could overflow long
        // and manifest as a negative value.
        return value < 0;
    }

    @Override
    public void flush() {
        if (db == null) {
            return;
        }
        try {
            dbAccessor.flush();
        } catch (final RocksDBException e) {
            throw new CacheException("Error while executing flush from store " + name, e);
        }
    }

    private void write(final WriteBatch batch) throws RocksDBException {
        db.write(wOptions, batch);
    }

    @Override
    public synchronized void close() {
        if (!open) {
            return;
        }

        open = false;
        closeOpenIterators();

        dbAccessor.close();
        userSpecifiedOptions.close();
        wOptions.close();
        fOptions.close();
        db.close();
        filter.close();
        cache.close();

        dbAccessor = null;
        userSpecifiedOptions = null;
        wOptions = null;
        fOptions = null;
        db = null;
        filter = null;
        cache = null;
    }

    private void closeOpenIterators() {
        final HashSet<KeyValueIterator<byte[], byte[]>> iterators = new HashSet<>(openIterators);
        if (iterators.size() != 0) {
            log.warn("Closing {} open iterators for store {}", iterators.size(), name);
            for (final KeyValueIterator<byte[], byte[]> iterator : iterators) {
                iterator.close();
            }
        }
    }

    interface RocksDBAccessor {

        void put(final byte[] key,
                 final byte[] value);

        void prepareBatch(final Map<byte[], byte[]> entries,
                          final WriteBatch batch) throws RocksDBException;

        byte[] get(final byte[] key) throws RocksDBException;

        KeyValueIterator<byte[], byte[]> range(byte[] from, boolean fromInclusive, byte[] to, boolean toInclusive);

        KeyValueIterator<byte[], byte[]> all();

        long approximateNumEntries() throws RocksDBException;

        void flush() throws RocksDBException;

        void addToBatch(final byte[] key,
                        final byte[] value,
                        final WriteBatch batch) throws RocksDBException;

        void close();
    }

    class SingleColumnFamilyAccessor implements RocksDBAccessor {
        private final ColumnFamilyHandle columnFamily;

        SingleColumnFamilyAccessor(final ColumnFamilyHandle columnFamily) {
            this.columnFamily = columnFamily;
        }

        @Override
        public void put(final byte[] key,
                        final byte[] value) {
            if (value == null) {
                try {
                    db.delete(columnFamily, wOptions, key);
                } catch (final RocksDBException e) {
                    // String format is happening in wrapping stores. So formatted message is thrown from wrapping stores.
                    throw new CacheException("Error while removing key from store " + name, e);
                }
            } else {
                try {
                    db.put(columnFamily, wOptions, key, value);
                } catch (final RocksDBException e) {
                    // String format is happening in wrapping stores. So formatted message is thrown from wrapping stores.
                    throw new CacheException("Error while putting key/value into store " + name, e);
                }
            }
        }

        @Override
        public void prepareBatch(final Map<byte[], byte[]> entries,
                                 final WriteBatch batch) throws RocksDBException {
            for (final Map.Entry<byte[], byte[]> entry : entries.entrySet()) {
                Objects.requireNonNull(entry.getKey(), "key cannot be null");
                addToBatch(entry.getKey(), entry.getValue(), batch);
            }
        }

        @Override
        public byte[] get(final byte[] key) throws RocksDBException {
            return db.get(columnFamily, key);
        }

        @Override
        public KeyValueIterator<byte[], byte[]> range(byte[] from, boolean fromInclusive, byte[] to, boolean toInclusive) {
            Comparator<byte[]> bytesComparator = new RocksDBKeyComparator<>(keySerde, comparator);

            if (from != null && to != null && bytesComparator.compare(from, to) > 0) {
                log.warn("Returning empty iterator for fetch with invalid key range: from > to. "
                    + "This may be due to serdes that don't preserve ordering when lexicographically comparing the serialized bytes. " +
                    "Note that the built-in numerical serdes do not follow this for negative numbers");
                return KeyValueIterators.emptyIterator();
            }

            return new RocksDBRangeIterator(
                name,
                db.newIterator(columnFamily),
                openIterators,
                from,
                fromInclusive,
                to,
                toInclusive,
                bytesComparator);
        }

        @Override
        public KeyValueIterator<byte[], byte[]> all() {
            final RocksIterator innerIterWithTimestamp = db.newIterator(columnFamily);
            innerIterWithTimestamp.seekToFirst();
            return new RocksDBIterator(name, innerIterWithTimestamp, openIterators);
        }

        @Override
        public long approximateNumEntries() throws RocksDBException {
            return db.getLongProperty(columnFamily, "rocksdb.estimate-num-keys");
        }

        @Override
        public void flush() throws RocksDBException {
            db.flush(fOptions, columnFamily);
        }

        @Override
        public void addToBatch(final byte[] key,
                               final byte[] value,
                               final WriteBatch batch) throws RocksDBException {
            if (value == null) {
                batch.delete(columnFamily, key);
            } else {
                batch.put(columnFamily, key, value);
            }
        }

        @Override
        public void close() {
            columnFamily.close();
        }
    }

    /**
     * Compares using comparator or natural ordering if null.
     * Called only by methods that have performed required type checks.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static int cpr(Comparator c, Object x, Object y) {
        return (c != null) ? c.compare(x, y) : ((Comparable) x).compareTo(y);
    }

    static final class SubCache<K, V> implements Cache<K, V> {

        /**
         * Underlying cache
         */
        private final RocksDBCache<K, V> m;
        /**
         * lower bound key, or null if from start
         */
        private final K lo;
        /**
         * upper bound key, or null if to end
         */
        private final K hi;
        /**
         * inclusion flag for lo
         */
        private final boolean loInclusive;
        /**
         * inclusion flag for hi
         */
        private final boolean hiInclusive;
        /**
         * direction
         */
        private final boolean isDescending;

        /**
         * Creates a new submap, initializing all fields.
         */
        SubCache(RocksDBCache<K, V> map,
                 K fromKey, boolean fromInclusive,
                 K toKey, boolean toInclusive,
                 boolean isDescending) {
            Comparator<? super K> cmp = map.comparator;
            if (fromKey != null && toKey != null &&
                cpr(cmp, fromKey, toKey) > 0)
                throw new IllegalArgumentException("inconsistent range");
            this.m = map;
            this.lo = fromKey;
            this.hi = toKey;
            this.loInclusive = fromInclusive;
            this.hiInclusive = toInclusive;
            this.isDescending = isDescending;
        }

        public void init() {
        }

        public void sync() {
        }

        public void flush() {
        }

        public void close() {
        }

        /* ----------------  Utilities -------------- */

        boolean tooLow(Object key, Comparator<? super K> cmp) {
            int c;
            return (lo != null && ((c = cpr(cmp, key, lo)) < 0 ||
                (c == 0 && !loInclusive)));
        }

        boolean tooHigh(Object key, Comparator<? super K> cmp) {
            int c;
            return (hi != null && ((c = cpr(cmp, key, hi)) > 0 ||
                (c == 0 && !hiInclusive)));
        }

        boolean inBounds(Object key, Comparator<? super K> cmp) {
            return !tooLow(key, cmp) && !tooHigh(key, cmp);
        }

        void checkKeyBounds(K key, Comparator<? super K> cmp) {
            if (key == null)
                throw new NullPointerException();
            if (!inBounds(key, cmp))
                throw new IllegalArgumentException("key out of range");
        }

        /* ----------------  Map API methods -------------- */

        public boolean containsKey(Object key) {
            if (key == null) throw new NullPointerException();
            return inBounds(key, m.comparator) && m.containsKey(key);
        }

        public V get(Object key) {
            if (key == null) throw new NullPointerException();
            return (!inBounds(key, m.comparator)) ? null : m.get(key);
        }

        public V put(K key, V value) {
            checkKeyBounds(key, m.comparator);
            return m.put(key, value);
        }

        public void putAll(Map<? extends K, ? extends V> entries) {
            for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
                put(e.getKey(), e.getValue());
            }
        }

        public V remove(Object key) {
            return (!inBounds(key, m.comparator)) ? null : m.remove(key);
        }

        public int size() {
            return entrySet().size();
        }

        public boolean isEmpty() {
            return entrySet().isEmpty();
        }

        public boolean containsValue(Object value) {
            throw new UnsupportedOperationException();
        }

        public void clear() {
            throw new UnsupportedOperationException();
        }

        /* ----------------  ConcurrentMap API methods -------------- */

        public V putIfAbsent(K key, V value) {
            checkKeyBounds(key, m.comparator);
            return m.putIfAbsent(key, value);
        }

        public boolean remove(Object key, Object value) {
            return inBounds(key, m.comparator) && m.remove(key, value);
        }

        public boolean replace(K key, V oldValue, V newValue) {
            checkKeyBounds(key, m.comparator);
            return m.replace(key, oldValue, newValue);
        }

        public V replace(K key, V value) {
            checkKeyBounds(key, m.comparator);
            return m.replace(key, value);
        }

        /* ----------------  SortedMap API methods -------------- */

        /**
         * Utility to create submaps, where given bounds override
         * unbounded(null) ones and/or are checked against bounded ones.
         */
        SubCache<K, V> newSubCache(K fromKey, boolean fromInclusive,
                                   K toKey, boolean toInclusive) {
            Comparator<? super K> cmp = m.comparator;
            if (isDescending) { // flip senses
                K tk = fromKey;
                fromKey = toKey;
                toKey = tk;
                boolean ti = fromInclusive;
                fromInclusive = toInclusive;
                toInclusive = ti;
            }
            if (lo != null) {
                if (fromKey == null) {
                    fromKey = lo;
                    fromInclusive = loInclusive;
                } else {
                    int c = cpr(cmp, fromKey, lo);
                    if (c < 0 || (c == 0 && !loInclusive && fromInclusive))
                        throw new IllegalArgumentException("key out of range");
                }
            }
            if (hi != null) {
                if (toKey == null) {
                    toKey = hi;
                    toInclusive = hiInclusive;
                } else {
                    int c = cpr(cmp, toKey, hi);
                    if (c > 0 || (c == 0 && !hiInclusive && toInclusive))
                        throw new IllegalArgumentException("key out of range");
                }
            }
            return new SubCache<>(m, fromKey, fromInclusive,
                toKey, toInclusive, isDescending);
        }

        public SubCache<K, V> subCache(K fromKey, boolean fromInclusive,
                                       K toKey, boolean toInclusive) {
            return newSubCache(fromKey, fromInclusive, toKey, toInclusive);
        }

        /* ---------------- Submap Views -------------- */

        public Set<K> keySet() {
            return Streams.streamOf(all())
                .map(kv -> kv.key)
                .collect(Collectors.toSet());
        }

        public Collection<V> values() {
            return Streams.streamOf(all())
                .map(kv -> kv.value)
                .collect(Collectors.toList());
        }

        public Set<Map.Entry<K, V>> entrySet() {
            return Streams.streamOf(all())
                .map(kv -> new AbstractMap.SimpleEntry<>(kv.key, kv.value))
                .collect(Collectors.toSet());
        }

        public KeyValueIterator<K, V> all() {
            return m.range(lo, loInclusive, hi, hiInclusive);
        }

        public KeyValueIterator<K, V> range(K fromKey, boolean fromInclusive,
                                            K toKey, boolean toInclusive) {
            Comparator<? super K> cmp = m.comparator;
            if (isDescending) { // flip senses
                K tk = fromKey;
                fromKey = toKey;
                toKey = tk;
                boolean ti = fromInclusive;
                fromInclusive = toInclusive;
                toInclusive = ti;
            }
            if (lo != null) {
                if (fromKey == null) {
                    fromKey = lo;
                    fromInclusive = loInclusive;
                } else {
                    int c = cpr(cmp, fromKey, lo);
                    if (c < 0 || (c == 0 && !loInclusive && fromInclusive))
                        throw new IllegalArgumentException("key out of range");
                }
            }
            if (hi != null) {
                if (toKey == null) {
                    toKey = hi;
                    toInclusive = hiInclusive;
                } else {
                    int c = cpr(cmp, toKey, hi);
                    if (c > 0 || (c == 0 && !hiInclusive && toInclusive))
                        throw new IllegalArgumentException("key out of range");
                }
            }
            return m.range(fromKey, fromInclusive, toKey, toInclusive);
        }
    }
}
