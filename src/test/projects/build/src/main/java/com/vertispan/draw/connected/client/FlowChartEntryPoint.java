package com.vertispan.draw.connected.client;

/*
 * #%L
 * Connected
 * %%
 * Copyright (C) 2017 - 2018 Vertispan
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

import java.util.Date;

import com.google.gwt.core.client.EntryPoint;
import com.vertispan.draw.connected.client.blank.DateTimeFormat;
import com.vertispan.draw.connected.client.blank.DateTimeFormat.PredefinedFormat;
import com.vertispan.draw.connected.client.data.Person;
import com.vertispan.draw.connected.client.lib.ConnectedComponent;
import com.vertispan.draw.connected.client.lib.Point;
import com.vertispan.draw.connected.client.lib.Rect;
import com.vertispan.draw.connected.shared.data.IsParentRelationship;
import elemental2.dom.DomGlobal;

/**
 * Demo app that uses the Connected module to edit some data
 */
public class FlowChartEntryPoint implements EntryPoint {
    private static int nextId = 0;
    private static String nextId() {
        return "" + (++nextId);
    }
    private static final DateTimeFormat format = DateTimeFormat.getFormat(PredefinedFormat.DATE_SHORT);
    public void onModuleLoad() {

        // Create the component, and tell it how to interact with our data (via lambdas)
        ConnectedComponent<Person, IsParentRelationship> boxesAndLines = new ConnectedComponent<>(
                Person::getId,
                person -> new Rect(person.getPos().getX(), person.getPos().getY(), 200, 150),
                rect -> {
                    Person person = new Person();
                    person.setId(nextId());
                    person.setPos(rect.center());
                    return person;
                },
                person -> person.getName() + "\n\n" + format.format(person.getBirthday()) + " \n   in " + person.getBirthplace(),
                (person, rect) -> person.setPos(rect.getTopLeft()),
                IsParentRelationship::getChildId,
                IsParentRelationship::getParentId,
                (p1, p2) -> new IsParentRelationship(p1.getId(), p2.getId())
        );

        // Listen for selection, so we can prompt the user in some way to edit the data
        boxesAndLines.addSelectionHandler(event -> {
            Person person = event.getSelectedItem();
            if (person.getBirthday() == null) {
                person.setBirthday(new Date());
            }
            String newName = DomGlobal.prompt("change name?", person.getName());
            person.setName(newName);
            boxesAndLines.updateBox(person);
        });

        // Sample data
        Person colin = new Person();
        colin.setId(nextId());
        colin.setBirthday(new Date(85, 3, 26));
        colin.setName("Colin");
        colin.setBirthplace("Annapolis, MD");
        colin.setSex("M");
        colin.setPos(new Point(10, 10));
        boxesAndLines.addBox(colin);

        Person karen = new Person();
        karen.setId(nextId());
        karen.setBirthday(new Date(84, 4, 13));
        karen.setName("Karen");
        karen.setBirthplace("Pontiac, MI");
        karen.setSex("F");
        karen.setPos(new Point(300, 10));
        boxesAndLines.addBox(karen);

        Person abigail = new Person();
        abigail.setId(nextId());
        abigail.setBirthday(new Date(116, 8, 24));
        abigail.setBirthplace("Maple Grove, MN");
        abigail.setName("Abigail");
        abigail.setSex("F");
        abigail.setPos(new Point(150, 200));
        boxesAndLines.addBox(abigail);

        // Sample relationships
        boxesAndLines.addLine(new IsParentRelationship(abigail.getId(), colin.getId()));
        boxesAndLines.addLine(new IsParentRelationship(abigail.getId(), karen.getId()));


        // Actually add the element to the body
        DomGlobal.document.body.appendChild(boxesAndLines.getElement());
    }
}
