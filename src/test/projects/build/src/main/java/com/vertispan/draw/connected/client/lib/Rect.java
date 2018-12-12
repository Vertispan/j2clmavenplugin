package com.vertispan.draw.connected.client.lib;

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
 * Simple rectangle model for drawing content and checking collisions.
 */
public class Rect {
    private final double x;
    private final double y;
    private final double w;
    private final double h;

    public Rect(double x, double y, double w, double h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getW() {
        return w;
    }

    public double getH() {
        return h;
    }

    public Point center() {
        return new Point(x + w/2, y + h/2);
    }

    public boolean contains(Point point) {
        return x <= point.getX() && x + w >= point.getX() &&
                y <= point.getY() && y + h >= point.getY();
    }

    public Rect translate(Point point) {
        return new Rect(x + point.getX(), y + point.getY(), w, h);
    }

    public Point getTopLeft() {
        return new Point(x, y);
    }
}
