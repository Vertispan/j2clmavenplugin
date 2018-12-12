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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.vertispan.draw.connected.client.blank.SelectionEvent;
import com.vertispan.draw.connected.client.blank.SelectionEvent.HasSelectionHandlers;
import com.vertispan.draw.connected.client.blank.SelectionEvent.SelectionHandler;
import com.vertispan.draw.connected.client.blank.StyleInjector;
import com.vertispan.draw.connected.client.lib.DragTracker.DragHandling;
import com.vertispan.draw.connected.shared.data.IsParentRelationship;
import elemental2.core.Global;
import elemental2.dom.CanvasRenderingContext2D;
import elemental2.dom.CanvasRenderingContext2D.FillStyleUnionType;
import elemental2.dom.CanvasRenderingContext2D.StrokeStyleUnionType;
import elemental2.dom.ClientRect;
import elemental2.dom.DomGlobal;
import elemental2.dom.Element;
import elemental2.dom.Event;
import elemental2.dom.HTMLButtonElement;
import elemental2.dom.HTMLCanvasElement;
import elemental2.dom.HTMLLabelElement;
import elemental2.dom.MouseEvent;
import elemental2.dom.NodeList;
import elemental2.dom.Request;
import elemental2.dom.RequestInit;
import elemental2.dom.Response;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;
import org.gwtproject.event.shared.EventBus;
import org.gwtproject.event.shared.HandlerRegistration;
import org.gwtproject.event.shared.SimpleEventBus;

import static elemental2.dom.DomGlobal.document;
import static elemental2.dom.DomGlobal.fetch;

/**
 * Base "widget" for this project. Not a GWT Widget, but wraps a dom element
 * and wires it up for easy use in a project.
 */
public class ConnectedComponent<B, L> implements HasSelectionHandlers<B> {
//    /**
//     * Simple interface to describe a tool's behavior when the user clicks or drags on
//     * the canvas. Both methods take the event to interact with, and a callback to
//     * request that an update occurs.
//     *
//     * @todo how to add extra pieces to the draw lifecycle?
//     */
//    public interface DrawTool {
//        boolean handleMouseDown(MouseEvent event, FrameScheduler frameScheduler);
//        boolean handleClick(MouseEvent event, FrameScheduler frameScheduler);
//    }
//    public interface FrameScheduler {
//        void request();
//    }

    private final EventBus handlerManager = new SimpleEventBus();

    //dom
    private Element root;
    private HTMLButtonElement drawBoxTool;
    private HTMLButtonElement drawLineTool;
    private HTMLButtonElement moveTool;
    private HTMLButtonElement remoteServiceTool;
    private HTMLLabelElement statusLabel;
    private Element canvasWrapper;
    private HTMLCanvasElement canvas;

    //logic
    public enum DrawMode {
        MOVE,
        DRAW_BOX,
        DRAW_LINE
    }

    ;
    private DrawMode drawMode;

    //wiring
    private Map<String, B> boxes = new LinkedHashMap<>();
    private final Function<B, String> boxIdFunct;
    private final Function<B, Rect> boxPosFunct;
    private final Function<Rect, B> boxCreator;
    private final Function<B, String> boxTextFunct;
    private final BiConsumer<B, Rect> boxPositionUpdater;

    private LinkedHashSet<L> lines = new LinkedHashSet<>();
    private final Function<L, String> startFunct;
    private final Function<L, String> endFunct;
    private final BiFunction<B, B, L> lineCreator;

    private DragTracker dragTracker = new DragTracker();
    private B startingBoxForNewLine;
    private Point currentEndForNewLine;

    public ConnectedComponent(Function<B, String> boxIdFunct, Function<B, Rect> boxPosFunct, Function<Rect, B> boxCreator, Function<B, String> boxTextFunct, BiConsumer<B, Rect> boxPositionUpdater, Function<L, String> startFunct, Function<L, String> endFunct, BiFunction<B, B, L> lineCreator) {
        this.boxIdFunct = boxIdFunct;
        this.boxPosFunct = boxPosFunct;
        this.boxCreator = boxCreator;
        this.boxTextFunct = boxTextFunct;
        this.boxPositionUpdater = boxPositionUpdater;
        this.startFunct = startFunct;
        this.endFunct = endFunct;
        this.lineCreator = lineCreator;

        root = document.createElement("div");
        root.className = "boxes-and-lines";
        Element buttonBar = document.createElement("div");
        buttonBar.classList.add("button-bar");

        drawBoxTool = (HTMLButtonElement) document.createElement("button");
        drawBoxTool.onclick = this::drawBox;
        drawBoxTool.innerHTML = "Draw Box";
        drawBoxTool.className = "button";

        drawLineTool = (HTMLButtonElement) document.createElement("button");
        drawLineTool.onclick = this::drawLine;
        drawLineTool.innerHTML = "Draw Line";
        drawLineTool.className = "button";

        moveTool = (HTMLButtonElement) document.createElement("button");
        moveTool.onclick = this::move;
        moveTool.innerHTML = "Move";
        moveTool.className = "button";

        remoteServiceTool = (HTMLButtonElement) document.createElement("button");
        remoteServiceTool.onclick = this::callService;
        remoteServiceTool.innerHTML = "Remote call";
        remoteServiceTool.className = "button";

        statusLabel = (HTMLLabelElement) document.createElement("label");

        canvasWrapper = document.createElement("div");
        canvasWrapper.className = "canvas-wrapper";

        canvas = (HTMLCanvasElement) document.createElement("canvas");
//        canvas.width = 1000;
//        canvas.height = 1000;
        canvas.onmousedown = this::canvasMouseDown;//use for drags, captured events deal with the rest

        //TODO CSS that doesn't look terrible, and HTML template for this whole thing
        buttonBar.appendChild(drawBoxTool);
        buttonBar.appendChild(drawLineTool);
        buttonBar.appendChild(moveTool);
        buttonBar.appendChild(remoteServiceTool);
        buttonBar.appendChild(statusLabel);
        root.appendChild(buttonBar);
        canvasWrapper.appendChild(canvas);
        root.appendChild(canvasWrapper);

        setDrawMode(DrawMode.MOVE);

        StyleInjector.inject("html,body{width:100%;height:100%;margin:0;}\n" +
                                     "body { display: flex; }\n" +
                                     "\n" +
                                     "\n" +
                                     ".button { background-color: white; }\n" +
                                     "button.button-on { background-color:gray; }\n" +
                                     "\n" +
                                     ".boxes-and-lines { display: flex; flex-flow: row nowrap; align-items: stretch; flex: 1 1 auto; }\n" +
                                     "\n" +
                                     ".button-bar { flex: 0 1 auto; }\n" +
                                     ".button-bar button {display:block}\n" +
                                     "\n" +
                                     ".canvas-wrapper { flex: 1 1 auto; overflow: hidden; }");

        //TODO this will leak after widget is detached...
        DomGlobal.window.addEventListener("resize", event -> scheduleFrame());
    }

    public Element getElement() {
        return root;
    }

    public HandlerRegistration addSelectionHandler(SelectionHandler<B> selectionHandler) {
        return handlerManager.addHandler(SelectionEvent.getType(), selectionHandler);
    }

    public void fireEvent(SelectionEvent<B> gwtEvent) {
        handlerManager.fireEvent(gwtEvent);
    }

    public void setDrawMode(DrawMode drawMode) {
        if (this.drawMode != drawMode) {
            //TODO cancel current drag, if any, to allow this to work at any time

            //turn off all buttons
            NodeList<Element> buttons = root.querySelectorAll("button.button");
            for (int i = 0; i < buttons.getLength(); i++) {
                buttons.getAt(i).classList.remove("button-on");
            }

            //turn on currently set button
            switch (drawMode) {
                case MOVE:
                    moveTool.classList.add("button-on");
                    break;
                case DRAW_BOX:
                    drawBoxTool.classList.add("button-on");
                    break;
                case DRAW_LINE:
                    drawLineTool.classList.add("button-on");
                    break;
            }

            //actually set the current draw mode, so later mouse operations make sense
            this.drawMode = drawMode;
        }
    }

    private Void drawBox(Event event) {
        setDrawMode(DrawMode.DRAW_BOX);
        return null;
    }

    private Void drawLine(Event event) {
        setDrawMode(DrawMode.DRAW_LINE);
        return null;
    }

    private Void move(Event event) {
        setDrawMode(DrawMode.MOVE);
        return null;
    }

    private Void callService(Event event) {
        RequestInit requestInit = RequestInit.create();
        requestInit.setMethod("get");
        String url = "http://localhost:8080/app/greet?id=1";
        fetch(new Request(url, requestInit))
                .then(Response::json)
                .then(response -> {
                    IsParentRelationship isParentRelationship = new IsParentRelationship();
                    JsPropertyMap<String> parse = Js.cast(Global.JSON.parse(Global.JSON.stringify(response)));
                    isParentRelationship.setParentId(parse.get("parentid"));
                    isParentRelationship.setChildId(parse.get("childid"));
                    statusLabel.textContent = isParentRelationship.getParentId() + " ->" + isParentRelationship.getChildId();
                    return null;
                });
        return null;
    }

    private Void canvasMouseDown(Event event) {
        if (drawMode == DrawMode.DRAW_BOX) {
            //track mouse, but use drag tool to detect click to avoid moving to a new place
            dragTracker.start(event, new DragHandling() {
                @Override
                public void click(MouseEvent event) {
                    //create a box at mouse coords
                    Point mouse = pointFromMouseEvent(event);
                    B box = boxCreator.apply(new Rect(mouse.getX(), mouse.getY(), 10, 10));
                    addBox(box);
                    editBox(box);
                }
            });
            return null;
        } else if (drawMode == DrawMode.DRAW_LINE) {
            //mark the box where we are starting
            startingBoxForNewLine = boxAtPoint(pointFromMouseEvent((MouseEvent) event));
            dragTracker.start(event, new DragHandling() {
                @Override
                public void click(MouseEvent event) {
                    // ignore as a drag, perform edit instead
                    startingBoxForNewLine = null;
                    currentEndForNewLine = null;

                    B box = boxAtPoint(pointFromMouseEvent(event));
                    if (box != null) {
                        editBox(box);
                    }
                }

                @Override
                public void moveDrag(MouseEvent event) {
                    // move the point since the user dragged
                    currentEndForNewLine = pointFromMouseEvent(event);
                    scheduleFrame();
                }

                @Override
                public void endDrag(MouseEvent event) {
                    //create the line
                    B box = boxAtPoint(pointFromMouseEvent(event));
                    if (box != null) {
                        L line = lineCreator.apply(startingBoxForNewLine, box);
                        addLine(line); //includes scheduleFrame
                    }
                    startingBoxForNewLine = null;
                    currentEndForNewLine = null;
                    scheduleFrame();
                }

                @Override
                public void cancelDrag() {
                    startingBoxForNewLine = null;
                    currentEndForNewLine = null;
                }
            });
        } else if (drawMode == DrawMode.MOVE) {
            //(startmouse - endmouse) + startCoords
            //
            Point mouseStartPosition = pointFromMouseEvent((MouseEvent) event);
            B box = boxAtPoint(mouseStartPosition);

            final Rect start = box == null ? null : boxPosFunct.apply(box);
//            Point offset = start.getTopLeft().relativeTo(mouseStartPosition);
            dragTracker.start(event, new DragHandling() {
                @Override
                public void click(MouseEvent event) {
                    //leave it, perform an edit instead

                    B box = boxAtPoint(pointFromMouseEvent(event));
                    if (box != null) {
                        editBox(box);
                    }
                }

                @Override
                public void moveDrag(MouseEvent event) {
                    if (start == null) {
                        return;
                    }
                    Point currentMousePosition = pointFromMouseEvent(event);
                    Rect newBounds = start.translate(mouseStartPosition.relativeTo(currentMousePosition));
                    boxPositionUpdater.accept(box, newBounds);
                    scheduleFrame();
                }
            });
        }

        return null;
    }

    private B boxAtPoint(Point point) {
        return boxes.values().stream().filter(box -> boxPosFunct.apply(box).contains(point)).findFirst().orElse(null);
    }

    private Point pointFromMouseEvent(MouseEvent event) {
        //offset x/y relies on the mouse staying over the element
        return new Point(event.pageX - canvas.offsetLeft, event.pageY - canvas.offsetTop);
    }

    private void editBox(B box) {
        SelectionEvent.fire(this, box);
    }

    public void addLine(L line) {
        lines.add(line);
        scheduleFrame();
    }

    public void removeLine(L line) {
        lines.remove(line);
        scheduleFrame();
    }

    public void addBox(B box) {
        String id = boxIdFunct.apply(box);
        boxes.put(id, box);
        scheduleFrame();
    }

    public void removeBox(B box) {
        String id = boxIdFunct.apply(box);
        boxes.remove(id);
        lines.removeIf(line -> startFunct.apply(line).equals(id) || endFunct.apply(line).equals(id));
        scheduleFrame();
    }

    public void updateBox(B box) {
        String id = boxIdFunct.apply(box);
        boxes.put(id, box);

        scheduleFrame();
    }

    public List<B> getBoxes() {
        return new ArrayList<>(boxes.values());
    }

    public List<L> getLines() {
        return new ArrayList<>(lines);
    }

    private boolean frameScheduled = false;

    private void scheduleFrame() {
        if (frameScheduled) {
            return;
        }
        frameScheduled = true;
        DomGlobal.requestAnimationFrame(timestamp -> {
            frameScheduled = false;
            draw();
        });
    }

    private void draw() {
        //casts to get context, bug in elemental2 beta...
        final CanvasRenderingContext2D context = (CanvasRenderingContext2D) (Object) canvas.getContext("2d");

        //resize to fit, if needed (this is ... expensive to check, and wrong if we are on a devicePixelRatio!=1 screen)
        ClientRect size = canvasWrapper.getBoundingClientRect();
        if (size.height != canvas.height || size.width != canvas.width) {
            //assuming there is something to be gained by not tweaking these directly, but should measure...
//            Double devicePixelRatio = ((JsPropertyMap<Double>) DomGlobal.window).get("devicePixelRatio");
            canvas.height = (int) (size.height - 10);// * devicePixelRatio;
            canvas.width = (int) size.width;// * devicePixelRatio;
//            canvas.style.height = HeightUnionType.of(size.height + "px");
//            canvas.style.width = WidthUnionType.of(size.height + "px");
        }

        //remove all current content
        //TODO in the future detect changes and apply a clip?
        context.clearRect(0, 0, canvas.width, canvas.height);

        context.fillStyle = FillStyleUnionType.of("#ffffff");
        context.strokeStyle = StrokeStyleUnionType.of("#000000");
        context.font = "14px sans-serif";

        //draw all lines, then all boxes. boxes have a fill, so the lines always are from the center of a box, starting at the edge
        lines.forEach(line -> {
            B start = boxes.get(startFunct.apply(line));
            Point startPoint = boxPosFunct.apply(start).center();
            B end = boxes.get(endFunct.apply(line));
            Point endPoint = boxPosFunct.apply(end).center();
            context.beginPath();
            context.moveTo(startPoint.getX(), startPoint.getY());
            context.lineTo(endPoint.getX(), endPoint.getY());
            context.stroke();
        });

        //draw the line we're creating, if any
        if (startingBoxForNewLine != null && currentEndForNewLine != null) {
            Point startPoint = boxPosFunct.apply(startingBoxForNewLine).center();
            context.beginPath();
            context.moveTo(startPoint.getX(), startPoint.getY());
            context.lineTo(currentEndForNewLine.getX(), currentEndForNewLine.getY());
            context.stroke();
        }

        boxes.values().forEach(box -> {
            Rect position = boxPosFunct.apply(box);
            context.fillRect(position.getX(), position.getY(), position.getW(), position.getH());
            context.strokeRect(position.getX(), position.getY(), position.getW(), position.getH());

            int padding = 10;
            int fontHeight = 14;
            String[] lines = boxTextFunct.apply(box).split("\n");
            context.fillStyle = FillStyleUnionType.of("#000000");
            for (int lineNo = 0; lineNo < lines.length; lineNo++) {
                context.fillText(lines[lineNo], padding + position.getX(), fontHeight + padding + position.getY() + fontHeight * lineNo);
            }
            context.fillStyle = FillStyleUnionType.of("#ffffff");
        });
    }
}
