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

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.StringArrayOptionHandler;

import java.io.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Master application which calls all the other processes. It will run the regression, compare results, compute the
 * summary, then publish the summary.
 *
 * @author Peter Abeles
 */
public class RuntimeRegressionMasterApp {
    // name of directory with JMH results
    public static final String JMH_DIR = "jmh";

    @Option(name = "--SummaryOnly", usage = "If true it will only print out the summary from last time it ran")
    boolean doSummaryOnly = false;

    @Option(name = "--MinimumOnly", usage =
            "If true, it shouldn't compute main JMH results, but should re-run minimum finding")
    boolean doMinimumOnly = false;

    @Option(name = "-e", aliases = {"--EmailPath"}, usage = "Path to email login. If relative, relative to project.")
    String emailPath = "email_login.txt";

    @Option(name = "-r", aliases = {"--ResultsPath"}, usage = "Path to results directory. If relative, relative to project.")
    String resultsPath = RunAllBenchmarksApp.BENCHMARK_RESULTS_DIR;

    @Option(name = "--Timeout", usage = "JMH Timeout in minutes")
    long timeoutMin = RunAllBenchmarksApp.DEFAULT_TIMEOUT_MIN;

    @Option(name = "-b", aliases = {"--Benchmark"}, handler = StringArrayOptionHandler.class,
            usage = "Used to specify a subset of benchmarks to run. Default is to run them all.")
    List<String> benchmarkNames = new ArrayList<>();

    /** Tolerance used to decide if the difference in results are significant */
    public double significantFractionTol = 0.4;

    // Log error messages
    protected PrintStream logStderr;

    public void performRegression() {
        long startTime = System.currentTimeMillis();
        resultsPath = GenerateCode32.projectRelativePath(resultsPath);
        emailPath = GenerateCode32.projectRelativePath(emailPath);
        var email = new EmailResults();
        email.loadEmailFile(new File(emailPath));

        // Little bit of a hack, but if we want to just run the minimum search then this is the easiest way
        // to do it
        if (doMinimumOnly)
            doSummaryOnly = true;

        File currentResultsDir;

        if (doSummaryOnly) {
            currentResultsDir = selectMostRecentResults();
        } else {
            currentResultsDir = new File(resultsPath,System.currentTimeMillis()+"");
            var measure = new RunAllBenchmarksApp();
            measure.outputRelativePath = new File(currentResultsDir,JMH_DIR).getPath();
            measure.timeoutMin = timeoutMin;
            measure.userBenchmarkNames = benchmarkNames;
            measure.process();
        }
        System.out.println("Current Results: " + currentResultsDir.getPath());
        File currentJmhDir = new File(currentResultsDir,"jmh");

        try {
            logStderr = new PrintStream(new FileOutputStream(new File(currentResultsDir,"errors.txt")));

            File baselineDir = new File(resultsPath, "baseline");
            if (!baselineDir.exists()) {
                System.out.println("Baseline doesn't exist. Making current results the baseline");
                if (!currentResultsDir.renameTo(baselineDir)) {
                    logStderr.println("Failed to rename current results to baseline");
                }
                if (email.emailDestination != null) {
                    email.send("EJML Runtime Regression: Initialized", "Created new baseline");
                }
                return;
            }

            // Find the initial set of exceptions and attempt to determine if they are false positives
            Map<String, Double> currentResults = RuntimeRegressionUtils.loadJmhResults(currentJmhDir);
            Map<String, Double> baselineResults = RuntimeRegressionUtils.loadJmhResults(new File(baselineDir,JMH_DIR));

            if (doMinimumOnly || !doSummaryOnly) {
                rerunFailedRegressionTests(currentResultsDir, currentResults, baselineResults);
            }

            createSummary(email, currentResultsDir, currentResults, baselineResults,
                    System.currentTimeMillis()-startTime);
        } catch (IOException e) {
            e.printStackTrace(logStderr);
        } finally {
            if (logStderr!=null)
                logStderr.close();
        }

        logStderr = null;
    }

    /**
     * Re-reun regression tests to see if they are false positives
     */
    private void rerunFailedRegressionTests( File currentResultsDir,
                                             Map<String, Double> currentResults,
                                             Map<String, Double> baselineResults ) {
        Set<String> exceptions = RuntimeRegressionUtils.findRuntimeExceptions(
                baselineResults, currentResults, significantFractionTol);

        var findMinimum = new RunExceptionsFindMinimum();
        findMinimum.outputRelativePath = new File(currentResultsDir,"minimum").getPath();
        findMinimum.significantFractionTol = significantFractionTol;
        for (String name : exceptions) {
            findMinimum.addBenchmark(name, baselineResults.get(name));
        }
        findMinimum.process();

        // Update the results with latest times and updated list of exceptions
        for (String name : exceptions) {
            currentResults.put(name, findMinimum.getNameToResults().get(name));
        }
        exceptions.clear();
        exceptions.addAll(findMinimum.getFailedNames());

        // Save summary to a file
        RuntimeRegressionUtils.saveAllResults(currentResults, new File(currentResultsDir, "summary.txt").getPath());
    }

    private void createSummary(EmailResults email, File currentDirectory,
                               Map<String, Double> current, Map<String, Double> baseline, long elapsedTime) {
        // Compare the benchmark results and summarize
        var summary = new RuntimeRegressionSummary();
        summary.processingTimeMS = elapsedTime;
        summary.process(current, baseline);

        // Log results
        String subject = String.format("EJML Runtime Regression: Flagged %3d Exceptions=%3d",
                summary.getFlagged().size(),
                summary.getExceptions().size());

        String text = summary.createSummary();

        if (email.emailDestination != null) {
            email.send(subject, text);
        }

        try {
            System.out.println("Saving to "+new File(currentDirectory, "summary.txt").getAbsolutePath());
            var writer = new PrintWriter(new File(currentDirectory, "summary.txt"));
            writer.println(text);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace(logStderr);
            logStderr.flush();
        }

        System.out.println(text);
    }

    /**
     * Selects the valid results directory which the highest name
     */
    public File selectMostRecentResults() {
        File directory = new File(resultsPath);
        File[] children = directory.listFiles();
        if (children == null)
            throw new RuntimeException("Results path is empty");
        File selected = null;
        for (int i = 0; i < children.length; i++) {
            File f = children[i];
            if (!f.isDirectory() && !new File(f, "exceptions.txt").exists())
                continue;
            if (f.getName().equals("baseline"))
                continue;
            if (selected == null || f.getName().compareTo(selected.getName()) > 0)
                selected = f;
        }
        if (selected == null)
            throw new RuntimeException("No valid results");
        return selected;
    }

    public static String formatDate( Date date ) {
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz");
        dateFormat.setTimeZone(java.util.TimeZone.getTimeZone("Zulu"));
        return dateFormat.format(date);
    }

    public static void main( String[] args ) {
        RuntimeRegressionMasterApp regression = new RuntimeRegressionMasterApp();
        CmdLineParser parser = new CmdLineParser(regression);

        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            parser.getProperties().withUsageWidth(120);
            parser.printUsage(System.out);
            return;
        }

        regression.performRegression();
    }
}
