/*
 * Copyright 2014 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.logic.location;

import com.google.common.collect.Lists;
import org.terasology.entitySystem.Component;
import org.terasology.entitySystem.Owns;
import org.terasology.entitySystem.entity.EntityRef;
import org.terasology.math.Direction;
import org.terasology.math.geom.Matrix4f;
import org.terasology.math.geom.Quat4f;
import org.terasology.math.geom.Vector3f;
import org.terasology.network.Replicate;
import org.terasology.network.ReplicationCheck;
import org.terasology.reflection.metadata.FieldMetadata;
import org.terasology.rendering.nui.properties.TextField;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Component represent the location and facing of an entity in the world
 *
 */
public final class LocationComponent implements Component, ReplicationCheck {

    public boolean replicateChanges = true;

    // Relative to
    @Replicate
    EntityRef parent = EntityRef.NULL;

    @Replicate
    List<EntityRef> children = Lists.newArrayList();

    // Standard position/rotation
    @Replicate
    @TextField
    Vector3f position = new Vector3f();
    @Replicate
    Quat4f rotation = new Quat4f(0, 0, 0, 1);
    @Replicate
    float scale = 1.0f;
    @Replicate
    Vector3f lastPosition = new Vector3f();
    @Replicate
    Quat4f lastRotation = new Quat4f(0,0,0,1);

    public LocationComponent() {
    }

    public LocationComponent(Vector3f position) {
        setLocalPosition(position);
    }

    /**
     * @return The position of this component relative to any parent. Can be directly modified to update the component
     */
    public Vector3f getLocalPosition() {
        return position;
    }

    public void setLocalPosition(Vector3f newPos) {
        lastPosition.set(position);
        position.set(newPos);
    }

    /**
     * @return The direction this entity is facing, relative to any parent
     */
    public Vector3f getLocalDirection() {
        Vector3f result = Direction.FORWARD.getVector3f();
        getLocalRotation().rotate(result, result);
        return result;
    }

    /**
     * @return The rotation of this component, relative to any parent
     */
    public Quat4f getLocalRotation() {
        return rotation;
    }

    public void setLocalRotation(Quat4f newQuat) {
        lastRotation.set(rotation);
        rotation.set(newQuat);
    }

    /**
     * @param value The scale of this component
     */
    public void setLocalScale(float value) {
        this.scale = value;
    }

    public float getLocalScale() {
        return scale;
    }

    /**
     * @return A new vector containing the world location.
     */
    public Vector3f getWorldPosition() {
        return getWorldPosition(new Vector3f());
    }

    /**
     * @param output
     * @return output, which is populated with the world location of this entity
     */
    public Vector3f getWorldPosition(Vector3f output) {
        output.set(position);
        LocationComponent parentLoc = parent.getComponent(LocationComponent.class);
        while (parentLoc != null) {
            output.scale(parentLoc.scale);
            parentLoc.getLocalRotation().rotate(output, output);
            output.add(parentLoc.position);
            parentLoc = parentLoc.parent.getComponent(LocationComponent.class);
        }
        return output;
    }

    /**
     * Populates out with the world transform of this entity
     * @param out
     */
    public void getWorldTransform(Matrix4f out) {
        LocationComponent loc = parent.getComponent(LocationComponent.class);
        if (loc != null) {
            loc.getWorldTransform(out);
        }
        out.mul(new Matrix4f(rotation, position, scale));
    }

    /**
     * Populates out with the transform of this entity relative to the given entity, or the world transform if entity
     * is not in this entity's parent hierarchy
     * @param out
     * @param entity
     */
    public void getRelativeTransform(Matrix4f out, EntityRef entity) {
        if (!(entity.equals(parent))) {
            LocationComponent loc = parent.getComponent(LocationComponent.class);
            if (loc != null) {
                loc.getRelativeTransform(out, entity);
            }
        }
        out.mul(new Matrix4f(rotation, position, scale));
    }

    public Vector3f getWorldDirection() {
        Vector3f result = Direction.FORWARD.getVector3f();
        getWorldRotation().rotate(result, result);
        return result;
    }

    public Quat4f getWorldRotation() {
        return getWorldRotation(new Quat4f(0, 0, 0, 1));
    }

    public Quat4f getWorldRotation(Quat4f output) {
        output.set(rotation);
        LocationComponent parentLoc = parent.getComponent(LocationComponent.class);
        while (parentLoc != null) {
            output.mul(parentLoc.rotation, output);
            parentLoc = parentLoc.parent.getComponent(LocationComponent.class);
        }
        return output;
    }

    public float getWorldScale() {
        float result = scale;
        LocationComponent parentLoc = parent.getComponent(LocationComponent.class);
        while (parentLoc != null) {
            result *= parentLoc.getLocalScale();
            parentLoc = parentLoc.parent.getComponent(LocationComponent.class);
        }
        return result;
    }

    public void setWorldPosition(Vector3f value) {
        setLocalPosition(value);
        LocationComponent parentLoc = parent.getComponent(LocationComponent.class);
        if (parentLoc != null) {
            this.position.sub(parentLoc.getWorldPosition());
            this.position.scale(1f / parentLoc.getWorldScale());
            Quat4f rot = new Quat4f(0, 0, 0, 1);
            rot.inverse(parentLoc.getWorldRotation());
            rot.rotate(this.position, this.position);
        }
    }

    public void setWorldRotation(Quat4f value) {
        setLocalRotation(value);
        LocationComponent parentLoc = parent.getComponent(LocationComponent.class);
        if (parentLoc != null) {
            Quat4f worldRot = parentLoc.getWorldRotation();
            worldRot.inverse();
            this.rotation.mul(worldRot, this.rotation);
        }
    }

    public void setWorldScale(float value) {
        this.scale = value;
        LocationComponent parentLoc = parent.getComponent(LocationComponent.class);
        if (parentLoc != null) {
            this.scale /= parentLoc.getWorldScale();
        }
    }

    public EntityRef getParent() {
        return parent;
    }

    public Collection<EntityRef> getChildren() {
        return children;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof LocationComponent) {
            LocationComponent other = (LocationComponent) o;
            return other.scale == scale && Objects.equals(parent, other.parent) && Objects.equals(position, other.position) && Objects.equals(rotation, other.rotation);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(position, rotation, scale, parent);
    }

    @Override
    public boolean shouldReplicate(FieldMetadata<?, ?> field, boolean initial, boolean toOwner) {
        return initial || replicateChanges;
    }
}
