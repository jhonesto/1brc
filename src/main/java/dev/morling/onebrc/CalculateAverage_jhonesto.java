/*
 *  Copyright 2023 The original authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dev.morling.onebrc;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public class CalculateAverage_jhonesto {

    // +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    // ++++++++++++++++++++++++++++ CONSTANTS +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    // +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

    private static final String FILE = "./measurements.txt";
    private static final String READ = "r";

    private static final byte NEW_LINE = 10;
    private static final byte MOD_NUMBER = 48;
    private static final byte SEMICOLON = 59;
    private static final byte MAX_PER_LINE = 107; // 107 = 100 station + ;-99.9 + \n

    private static final int ZERO = 0;
    private static final int ONE = 1;
    private static int MAX_BUFFER_SIZE = 2_147_483_647 - 200;

    private static final short RADIX = 10;

    private static final long ZERO_LONG = 0L;

    private static final double TEN_DOUBLE = 10.0d;

    private static final char LINE_FEED = '\n';
    private static final char MINUS = '-';
    private static final char PERIOD = '.';

    // +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    // ++++++++++++++++++++++++++++++++++ MAIN +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    // +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

    public static void main(String[] args) throws FileNotFoundException {

        int NPROC = Runtime.getRuntime().availableProcessors();

        File file = new File(FILE);

        long fileSize = file.length();

        int nbuffers;

        int[] buffers;

        if (fileSize > MAX_BUFFER_SIZE) {

            if (fileSize / NPROC < MAX_BUFFER_SIZE)
                MAX_BUFFER_SIZE = (int) (fileSize / NPROC);

            nbuffers = (int) (fileSize / MAX_BUFFER_SIZE) +
                    (int) (fileSize % MAX_BUFFER_SIZE > ZERO ? ONE : ZERO);

            buffers = new int[nbuffers];

            for (int i = ZERO; i < buffers.length; i++) {
                buffers[i] = i == nbuffers - ONE ? (int) (fileSize % MAX_BUFFER_SIZE) : MAX_BUFFER_SIZE;
            }

        }
        else {
            // I don't care about speed here. It's a ONE BILLION ROWS challenge.
            nbuffers = ONE;
            buffers = new int[nbuffers];
            buffers[ZERO] = (int) fileSize;

        }

        try (

                FileChannel channel = new RandomAccessFile(file, READ).getChannel()) {

            long eof[] = getEOP(buffers, MAX_BUFFER_SIZE, file);

            List<Partition> parts = new ArrayList<>(nbuffers);

            long acc = ZERO_LONG;

            for (int i = ZERO; i < buffers.length; i++) {

                long start = i == ZERO ? ZERO : acc;
                int size = (int) (eof[i] - acc);

                acc = eof[i];

                MappedByteBuffer map = channel.map(FileChannel.MapMode.READ_ONLY, start, size);

                Partition part = new Partition(map, i, buffers.length - ONE, size);

                parts.add(part);

            }

            eof = null;
            buffers = null;

            ExecutorService es = Executors.newFixedThreadPool(parts.size());

            List<Future<List<Result>>> futures = new ArrayList<>();

            for (Partition part : parts) {
                futures.add(es.submit(part::compute));
            }

            List<List<Result>> lista = new ArrayList<>();

            for (int i = futures.size() - ONE; i >= ZERO; i--) {
                List<Result> out = futures.get(i).get();
                lista.add(out);
            }

            es.shutdown();

            var result = lista
                    .stream()
                    .flatMap(List::stream)
                    .collect(Collectors.groupingBy(Result::getStation))
                    .values()
                    .parallelStream()
                    .map(results -> results.stream()
                            .reduce((r1, r2) -> {
                                r1.min = r1.min < r2.min ? r1.min : r2.min;
                                r1.max = r1.max > r2.max ? r1.max : r2.max;
                                r1.sum += r2.sum;
                                r1.count += r2.count;
                                return r1;
                            }))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList()
                    .stream()
                    .sorted(Result::compareTo)
                    .map(Result::toString)
                    .collect(Collectors.joining(", "));

            System.out.println("{" + result + "}");

        }
        catch (IOException | ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }

    }

    // +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    // ++++++++++++++++++++++++++++ CLASSES ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    // +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

    private static class Partition {

        MappedByteBuffer map;

        long position;

        int last;

        int size;

        HashMap<Integer, Measurement> table = new HashMap<>(785, 0.8f);

        public Partition(MappedByteBuffer map, long position, int last, int size) {
            this.map = map;
            this.position = position;
            this.last = last;
            this.size = size;
        }

        public List<Result> compute() {

            byte[] m = new byte[MAX_PER_LINE];

            byte lf;

            for (int i = ZERO, pos = ZERO; i < this.size; i += ONE) {

                lf = this.map.get();

                if (lf == NEW_LINE) {

                    byte[] measurement = asByteArrayCopy(m, pos + ONE);

                    resolveMeasurement(measurement, table);

                    pos = ZERO;
                    continue;
                }

                m[pos += ONE] = lf;

            }

            m = null;

            List<Result> resultList = new ArrayList<>(table.size());

            table.forEach((key, value) -> resultList.addAll(value.getResultList()));

            return resultList;
        }

    }

    private static class Measurement {

        private final Result result;

        private Measurement next;

        public Measurement(byte[] station, short t) {

            this.result = new Result(station, t, t, t, ONE);

        }

        public void setTemperature(short t) {

            result.max = result.max < t ? t : result.max;
            result.min = result.min > t ? t : result.min;
            result.sum = result.sum + t;
            result.count = result.count + ONE;

        }

        public List<Result> getResultList() {

            return getResults(this);

        }

    }

    private static class Result {

        byte[] station;
        short min;
        short max;
        int sum;
        int count;

        public Result(byte[] station, short min, short max, int sum, int count) {
            this.station = station;
            this.min = min;
            this.max = max;
            this.sum = sum;
            this.count = count;

        }

        public String getStation() {
            return new String(station, StandardCharsets.UTF_8).trim();
        }

        public String toString() {
            return String.format("%s=%.1f/%.1f/%.1f",
                    new String(this.station, StandardCharsets.UTF_8).trim(),
                    Math.round(min) / TEN_DOUBLE,
                    Math.round(sum / (double) count) / TEN_DOUBLE,
                    Math.round(max) / TEN_DOUBLE);
        }

        public int compareTo(Result other) {
            return new String(this.station).compareTo(new String(other.station));
        }
    }

    // +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    // ++++++++++++++++++++++++++++ UTILS ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    // +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

    // Get the end of partitions; b -> buffer, mbs -> max_buffer_size
    private static long[] getEOP(int[] b, int mbs, File file) {

        long eof[] = new long[b.length];

        try (RandomAccessFile raf = new RandomAccessFile(file, READ)) {

            for (int i = ZERO; i < b.length; i++) {

                long size = ((long) mbs * i) +
                        (long) (i == b.length - ONE ? b[i] : mbs);

                raf.seek((size - ONE));

                while (raf.read() != LINE_FEED) {
                    raf.seek(--size - ONE);
                }

                eof[i] = size;
            }
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }

        return eof;
    }

    public static byte[] asByteArrayCopy(byte[] arr, int length) {
        byte[] b = new byte[length];
        System.arraycopy(arr, ZERO, b, ZERO, length);
        return b;
    }

    private static void resolveMeasurement(byte[] measurement, HashMap<Integer, Measurement> table) {

        short temperature = 0;

        int j = measurement.length - ONE;

        for (short exp = ZERO; j > ZERO; j -= ONE, exp += ONE) {

            if (measurement[j] == SEMICOLON) {
                break;
            }

            if (measurement[j] == MINUS) {
                temperature *= -ONE;
            }
            else if (measurement[j] == PERIOD) {
                exp -= ONE;
            }
            else {
                temperature += (short) ((measurement[j] % MOD_NUMBER) * pow(RADIX, exp));
            }

        }

        byte[] station = asByteArrayCopy(measurement, j);

        int hash = getHash(station);

        Measurement m = table.get(hash);

        if (m != null) {
            // Thanks to Roy's comment, I understood how to handle the situation appropriately.
            if (Arrays.equals(m.result.station, station)) {
                m.setTemperature(temperature);
                return;
            }

            addNextMeasurement(m, station, temperature);

        }
        else {
            m = new Measurement(station, temperature);
            table.put(hash, m);
        }

    }

    private static void addNextMeasurement(Measurement m, byte[] station, short temperature) {

        Measurement tmp = m.next;

        while (tmp != null) {
            if (Arrays.equals(tmp.result.station, station)) {
                tmp.setTemperature(temperature);
                return;
            }
            else {
                tmp = tmp.next;
            }
        }

        m.next = new Measurement(station, temperature);
    }

    private static List<Result> getResults(Measurement m) {

        List<Result> results = new ArrayList<>();

        results.add(m.result);

        m = m.next;

        while (m != null) {
            results.add(m.result);
            m = m.next;
        }

        return results;
    }

    private static short pow(short base, short exponent) {

        short result = ONE;

        for (short i = ZERO; i < exponent; i += ONE) {
            result *= base;
        }

        return result;

    }

    public static int getHash(byte[] b) {
        return Arrays.hashCode(b);
    }

}
