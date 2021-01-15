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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Utility functions for dealing with JMh log results.
 *
 * @author Peter Abeles
 */
public class RuntimeRegressionUtils {
    /**
     * Loads all the JMH results in a directory and puts it into a map.
     */
    public static Map<String, Double> loadJmhResults( File directory ) throws IOException {
        Map<String, Double> results = new HashMap<>();
        var parser = new ParseBenchmarkCsv();

        File[] children = directory.listFiles();
        if (children == null)
            return results;

        for (int i = 0; i < children.length; i++) {
            File f = children[i];
            if (!f.isFile() || !f.getName().endsWith(".csv"))
                continue;
            parser.parse(new FileInputStream(f));
            for (ParseBenchmarkCsv.Result r : parser.results) {
                String parameters = "";
                for (String p : r.parameters) {
                    parameters += ":" + p;
                }
                results.put(r.benchmark + parameters, r.getMilliSecondsPerOp());
            }
        }

        return results;
    }

    /**
     * For every comparable result, see if the current performance shows any regressions
     *
     * @param tolerance fractional tolerance
     */
    public static Set<String> findRuntimeExceptions( Map<String, Double> baseline,
                                                     Map<String, Double> current,
                                                     double tolerance ) {
        Set<String> exceptions = new HashSet<>();

        for (String name : baseline.keySet()) {
            double valueBaseline = baseline.get(name);
            if (!current.containsKey(name))
                continue;
            double valueCurrent = current.get(name);

            if (valueCurrent/valueBaseline - 1.0 <= tolerance)
                continue;

            exceptions.add(name);
        }

        return exceptions;
    }

    public static void saveAllResults( Map<String, Double> results, String path ) {
        String text = "# Results Summary";
        for (String key : results.keySet()) {
            text += key +","+results.get(key) + "\n";
        }
        try {
            System.out.println("Saving to " + path);
            var writer = new PrintWriter(path);
            writer.println(text);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
