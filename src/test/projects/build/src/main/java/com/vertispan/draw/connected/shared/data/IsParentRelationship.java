package com.vertispan.draw.connected.shared.data;

/*
 * #%L
 * connected
 * %%
 * Copyright (C) 2017 Vertispan
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

/**
 * Simple "edge" or "line" data model.
 */
public class IsParentRelationship {
    private String childId;
    private String parentId;


    public IsParentRelationship(String childId, String parentId) {
        this.childId = childId;
        this.parentId = parentId;
    }

    public IsParentRelationship() {
    }

    public String getChildId() {
        return childId;
    }

    public void setChildId(String childId) {
        this.childId = childId;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        IsParentRelationship that = (IsParentRelationship) o;

        if (!childId.equals(that.childId)) return false;
        return parentId.equals(that.parentId);

    }

    @Override
    public int hashCode() {
        int result = childId.hashCode();
        result = 31 * result + parentId.hashCode();
        return result;
    }
}
