package ${package};

import elemental2.dom.DomGlobal;
import elemental2.dom.HTMLButtonElement;
import elemental2.dom.HTMLDivElement;

public class ${module} implements EntryPoint {

    public static final String HELLO_WORLD = "Hello J2CL world!";

    @Override
    public void onModuleLoad() {
        HTMLDivElement wrapper = (HTMLDivElement) DomGlobal.document.createElement("div");
        wrapper.style.textAlign = "center";

        HTMLButtonElement btn = (HTMLButtonElement) DomGlobal.document.createElement("button");
        btn.style.position = "absolute";
        btn.style.top = "50%";
        btn.textContent = "J2CL !!!";

        btn.addEventListener("click", evt -> btn.textContent = helloWorldString());

        wrapper.appendChild(btn);

        DomGlobal.document.body.appendChild(wrapper);
    }

    String helloWorldString() {
        return HELLO_WORLD;
    }
}
