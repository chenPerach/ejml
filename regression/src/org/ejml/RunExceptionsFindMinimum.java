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

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * This will run a specific benchmark inside a benchmark class
 *
 * @author Peter Abeles
 */
public class RunExceptionsFindMinimum extends JmhRunnerBase {

    /** Stores the baseline results that are expected */
    public String baselineDirectory = "runtime_regression/baseline";

    /** Stores results from the most recent run that is being tested */
    public String currentDirectory = "runtime_regression/current";

    /** Tolerance used to decide if the difference in results are significant */
    public double significantFractionTol = 0.4;

    /** The maximum number of times it will run a test and see if it's within tolerance */
    public int maxIterations = 10;

    private final List<BenchmarkInfo> benchmarks = new ArrayList<>();

    public void addBenchmark( String name, double targetTimeMS ) {
        benchmarks.add(new BenchmarkInfo(name,targetTimeMS));
    }

    public void process() throws IOException {
        PrintStream stderr = System.err;
        logExceptions = null;
        logRuntimes = null;
        logStderr = null;
        try {
            long time0 = System.currentTimeMillis();
            outputDirectory = new File(GenerateCode32.projectRelativePath("tmp"));
            if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
                throw new UncheckedIOException(new IOException("Failed to mkdirs output: " + outputDirectory.getPath()));
            }
            System.out.println("Output Directory: " + outputDirectory.getAbsolutePath());
            try {
                logExceptions = new PrintStream(new File(outputDirectory, "exceptions.txt"));
                logRuntimes = new PrintStream(new File(outputDirectory, "runtime.txt"));
                // print stderr to console and save to a file
                logStderr = new PrintStream(new File(outputDirectory, "stderr.txt"));
                System.setErr(new PrintStream(new RunAllBenchmarksApp.MirrorStream(stderr, logStderr)));
                logRuntimes.println("# How long each benchmark took\n");
                logRuntimes.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            // Run benchmarks by finding automatically or by manually specifying them
            ParseBenchmarkCsv parseResults = new ParseBenchmarkCsv();
            for (int iteration = 0; iteration < maxIterations; iteration++) {
                for (int i = benchmarks.size()-1; i >= 0; i--) {
                    BenchmarkInfo info = benchmarks.get(i);
                    runBenchmark(info.path, true);
                    parseResults.parse(new FileInputStream(new File(outputDirectory,info.path+".csv")));
                    if (parseResults.results.size()!=1)
                        throw new RuntimeException("Expected only one result not "+parseResults.results.size());
                    double score = parseResults.results.get(0).getMilliSecondsPerOp();

                    // save the best score for later
                    info.bestFound = Math.min(score, info.bestFound);

                    // If it is equal to or better than the target score, remove it from the list
                    double fractionalDifference = score/info.targetScore-1.0;
                    if (fractionalDifference < significantFractionTol) {
                        // TODO log this to a file
                        System.out.printf("Accepted: Trial=%2d score=%7.3f name=%s\n",iteration,fractionalDifference,info.path);
                        benchmarks.remove(i);
                    } else {
                        System.out.printf("Rejected: Trial=%2d score=%7.3f name=%s\n",iteration,fractionalDifference,info.path);
                    }
                }
            }

            if (benchmarks.isEmpty()) {
                System.out.println("No remaining exceptions");
            } else {
                for (BenchmarkInfo info : benchmarks) {
                    double fractionalDifference = info.bestFound/info.targetScore-1.0;
                    System.out.printf("Failure: score=%7.3f name=%s\n",fractionalDifference,info.path);
                }
            }

            // Print out the total time the benchmark took
            long time1 = System.currentTimeMillis();
            long totalTimeMS = time1 - time0;
            int seconds = (int)(totalTimeMS/1000)%60;
            int minutes = (int)((totalTimeMS/(1000*60))%60);
            int hours = (int)((totalTimeMS/(1000*60*60))%24);
            logRuntimes.printf("\nTotal Elapsed Time is %2d:%2d:%2d\n", hours, minutes, seconds);
            System.out.printf("\nTotal Elapsed Time is %2d:%2d:%2d\n", hours, minutes, seconds);
        } finally {
            // Stop mirroring stderr
            System.setErr(stderr);

            // Close all log files
            logStderr.close();
            logExceptions.close();
            logRuntimes.close();

            System.out.println("Done!");
        }
    }

    private static class BenchmarkInfo {
        public String path;
        public double targetScore;
        public double bestFound;

        public BenchmarkInfo( String path, double targetScore ) {
            this.path = path;
            this.targetScore = targetScore;
            bestFound = Double.MAX_VALUE;
        }
    }

    public static void main( String[] args ) throws IOException {
        var app = new RunExceptionsFindMinimum();
        app.addBenchmark("org.ejml.dense.block.BenchmarkMatrixMult_DDRB.mult",242.377049833333);
        app.process();
    }
}
