/*
 * Copyright (c) 2021, Peter Abeles. All Rights Reserved.
 *
 * This file is part of Efficient Java Matrix Library (EJML).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ejml;

import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.TimeUnit;

/**
 * Common class for running JMH benchmarks and logging errors and other information
 */
public class JmhRunnerBase {

    public static long DEFAULT_TIMEOUT_MIN = 3;

    /**
     * How long a single JMH test has before it times out. This should be kept fairly small since this is designed
     * to catch regressions not evaluate performance on large datasets
     */
    public long timeoutMin = DEFAULT_TIMEOUT_MIN;


    // Directory it saved results too
    public File outputDirectory;

    // Print streams to different files
    protected PrintStream logExceptions;
    protected PrintStream logRuntimes;
    protected PrintStream logStderr;

    /**
     * Runs the benchmark and saves the results to disk
     *
     * @param exact If true it will only run tests which match that name exactly. Good for single runs
     */
    public void runBenchmark( String benchmarkName, boolean exact ) {
        System.out.println("Running " + benchmarkName);
        logRuntimes.printf("%-80s ", benchmarkName.substring(9));
        logRuntimes.flush();

        long time0 = System.currentTimeMillis();
        Options opt = new OptionsBuilder()
                .include(exact?"\\b"+benchmarkName+"\\b":benchmarkName)
                // Using average since it seems to have less loss of precision across a range of speeds
                .mode(Mode.AverageTime)
                // Using nanoseconds since it seems to have less loss of precision for very fast and slow operations
                .timeUnit(TimeUnit.NANOSECONDS)
                // The number of times the benchmark is run  is basically at the bare minimum to speed everything up.
                // Otherwise it would take an excessive amount of time
                .warmupTime(TimeValue.seconds(1))
                .warmupIterations(2)
                .measurementTime(TimeValue.seconds(1))
                .measurementIterations(3)
                .timeout(TimeValue.minutes(timeoutMin))
                .forks(1)
                .shouldFailOnError(true)
                .shouldDoGC(true)
                .resultFormat(ResultFormatType.CSV)
                .result(outputDirectory.getPath() + "/" + benchmarkName + ".csv")
                .build();

        try {
            Runner runner = new Runner(opt);
            runner.run();
            // There is a weird halting issue after it runs for a while on one machine. This is an attempt to see
            // if it's GC related.
            System.out.println("System GC run = "+runner.runSystemGC());
        } catch (RunnerException e) {
            e.printStackTrace();
            logException("Exception running " + benchmarkName + " : " + e.getMessage());
        }
        long time1 = System.currentTimeMillis();
        logStderr.flush();
        logRuntimes.printf("%7.2f (min)\n", (time1 - time0)/(60_000.0));
        logRuntimes.flush();
    }

    private void logException( String message ) {
        logExceptions.println(message);
        logExceptions.flush();
    }

    /** Copies the stream into two streams */
    public static class MirrorStream extends OutputStream {
        PrintStream outA, outB;
        public MirrorStream( PrintStream outA, PrintStream outB ) { this.outA = outA; this.outB = outB; }
        @Override public void write( int b ) { outA.write(b); outB.write(b); }
        @Override public void write( byte[] b, int off, int len ) { outA.write(b, off, len); outB.write(b, off, len); }
        @Override public void flush() { outA.flush(); outB.flush(); }
        @Override public void close() { outA.close(); outB.close(); }
    }
}
