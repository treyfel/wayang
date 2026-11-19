/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.wayang.basic.operators;

import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.spatial.SpatialPredicate;
import org.apache.wayang.core.optimizer.ProbabilisticDoubleInterval;

import java.util.Locale;

/**
 * Default cardinality assumptions for {@link SpatialFilterOperator} and {@link SpatialJoinOperator}.
 *
 * <p>Each default can be overridden per predicate in the {@link Configuration}:</p>
 * <pre>
 * wayang.basic.spatialfilter.selectivity.&lt;predicate&gt;.lower / .upper
 * wayang.basic.spatialfilter.selectivity.confidence
 * wayang.basic.spatialjoin.matchfactor.&lt;predicate&gt;.lower / .upper
 * wayang.basic.spatialjoin.matchfactor.confidence
 * </pre>
 * <p>where {@code <predicate>} is the lower-case {@link SpatialPredicate} name, e.g. {@code intersects}.</p>
 */
final class SpatialCardinalityEstimates {

    static final String FILTER_KEY_PREFIX = "wayang.basic.spatialfilter.selectivity";

    static final String JOIN_KEY_PREFIX = "wayang.basic.spatialjoin.matchfactor";

    /**
     * Confidence of the built-in defaults. Kept low so that the optimizer treats them as rough guesses.
     */
    private static final double DEFAULT_CONFIDENCE = 0.5d;

    private SpatialCardinalityEstimates() {
    }

    /**
     * Fraction of the input that passes a spatial filter against a single reference geometry.
     */
    static ProbabilisticDoubleInterval filterSelectivity(SpatialPredicate predicate, Configuration configuration) {
        final double[] defaults;
        if (predicate == null) {
            defaults = new double[]{0.001, 0.5};
        } else {
            switch (predicate) {
                case INTERSECTS:
                case WITHIN:
                    defaults = new double[]{0.001, 0.5};
                    break;
                case CONTAINS:
                    defaults = new double[]{0.0001, 0.05};
                    break;
                case EQUALS:
                    defaults = new double[]{0, 0.01};
                    break;
                default: // OVERLAPS, TOUCHES, CROSSES
                    defaults = new double[]{0.0001, 0.1};
            }
        }
        return lookup(FILTER_KEY_PREFIX, predicate, defaults, configuration);
    }

    /**
     * Output size of a spatial join as a multiple of its larger input, i.e., the expected number of
     * matches per element. Spatial joins usually preserve density (e.g., each point lies in about one
     * polygon), so this scales far better than a fraction of the cross product.
     */
    static ProbabilisticDoubleInterval joinMatchFactor(SpatialPredicate predicate, Configuration configuration) {
        final double[] defaults;
        if (predicate == null) {
            defaults = new double[]{0.1, 10};
        } else {
            switch (predicate) {
                case INTERSECTS:
                    defaults = new double[]{0.1, 10};
                    break;
                case CONTAINS:
                case WITHIN:
                    defaults = new double[]{0.1, 2};
                    break;
                case EQUALS:
                    defaults = new double[]{0, 1};
                    break;
                default: // OVERLAPS, TOUCHES, CROSSES
                    defaults = new double[]{0.01, 5};
            }
        }
        return lookup(JOIN_KEY_PREFIX, predicate, defaults, configuration);
    }

    private static ProbabilisticDoubleInterval lookup(String prefix,
                                                      SpatialPredicate predicate,
                                                      double[] defaults,
                                                      Configuration configuration) {
        final String predicateKey = prefix + "." + (predicate == null ? "default" : predicate.name().toLowerCase(Locale.ROOT));
        final double lower = configuration.getDoubleProperty(predicateKey + ".lower", defaults[0]);
        final double upper = configuration.getDoubleProperty(predicateKey + ".upper", defaults[1]);
        final double confidence = configuration.getDoubleProperty(prefix + ".confidence", DEFAULT_CONFIDENCE);
        return new ProbabilisticDoubleInterval(Math.min(lower, upper), Math.max(lower, upper), confidence);
    }

    /**
     * Multiplies without overflowing past {@link Long#MAX_VALUE}.
     */
    static long saturatedProduct(double a, double b) {
        final double product = a * b;
        return product >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) product;
    }
}
