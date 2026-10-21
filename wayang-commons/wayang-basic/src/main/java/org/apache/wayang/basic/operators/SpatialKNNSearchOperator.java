package org.apache.wayang.basic.operators;

import org.apache.wayang.core.api.Configuration;
import org.apache.wayang.core.api.spatial.SpatialGeometry;
import org.apache.wayang.core.api.spatial.SpatialPredicate;
import org.apache.wayang.core.function.FunctionDescriptor;
import org.apache.wayang.core.function.TransformationDescriptor;
import org.apache.wayang.core.optimizer.cardinality.CardinalityEstimator;
import org.apache.wayang.core.optimizer.cardinality.DefaultCardinalityEstimator;
import org.apache.wayang.core.plan.wayangplan.UnaryToUnaryOperator;
import org.apache.wayang.core.types.DataSetType;

import java.util.Optional;

/**
 * This operator returns a new dataset after filtering by applying a knn-search operation.
 */
public class SpatialKNNSearchOperator<Type> extends UnaryToUnaryOperator<Type, Type> {
    protected final Integer k;
    protected final TransformationDescriptor<Type, SpatialGeometry> keyDescriptor;
    protected final SpatialGeometry referenceGeometry;

    @SuppressWarnings("unchecked")
    public SpatialKNNSearchOperator(Integer k,
                                    FunctionDescriptor.SerializableFunction<Type, ? extends SpatialGeometry> keyExtractor,
                                    DataSetType<Type> inputClassDatasetType,
                                    SpatialGeometry geometry) {
        super(inputClassDatasetType, inputClassDatasetType, true);
        this.k = k;
        this.keyDescriptor = new TransformationDescriptor<>(
                (FunctionDescriptor.SerializableFunction<Type, SpatialGeometry>) (FunctionDescriptor.SerializableFunction) keyExtractor,
                inputClassDatasetType.getDataUnitType().getTypeClass(), SpatialGeometry.class);
        this.referenceGeometry = geometry;
    }

    /**
     * Copies an instance (exclusive of broadcasts).
     *
     * @param that that should be copied
     */
    public SpatialKNNSearchOperator(SpatialKNNSearchOperator<Type> that) {
        super(that);
        this.k = that.k;
        this.keyDescriptor = that.keyDescriptor;
        this.referenceGeometry = that.referenceGeometry;
    }

    public SpatialGeometry getReferenceGeometry() {
        return this.referenceGeometry;
    }

    public TransformationDescriptor<Type, SpatialGeometry> getKeyDescriptor() {
        return this.keyDescriptor;
    }

    public Integer getK() {
        return this.k;
    }

    /**
     * The output holds the k nearest elements, or the whole input if it has fewer than k.
     */
    @Override
    public Optional<CardinalityEstimator> createCardinalityEstimator(
            final int outputIndex,
            final Configuration configuration) {
        assert outputIndex == 0;
        final long k = this.k;
        return Optional.of(new DefaultCardinalityEstimator(1d, 1, false, inputCards -> Math.min(k, inputCards[0])));
    }

}
