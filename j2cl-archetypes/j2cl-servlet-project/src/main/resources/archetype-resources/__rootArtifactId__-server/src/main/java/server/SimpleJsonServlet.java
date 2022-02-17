package ${package}.server;

import java.io.IOException;
import java.util.regex.Pattern;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletOutputStream;

/**
 * A very simple servlet, avoiding any server libraries, with an overly-simple JSON
 * encoding strategy.
 */
@WebServlet(name = "SimpleJsonServlet", urlPatterns = {"/hello.json"})
public class SimpleJsonServlet extends HttpServlet {
    private static final Pattern simpleAsciiChars = Pattern.compile("^[a-zA-Z0-9]*$");

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String name = request.getParameter("name");
        if (!simpleAsciiChars.matcher(name).matches()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        response.setContentType("application/json;charset=UTF-8");

        try (ServletOutputStream out = response.getOutputStream()) {
            out.print("{\"response\":\"Hello, " + name + "\"}");
        }
    }

}