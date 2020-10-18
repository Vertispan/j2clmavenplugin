package net.cardosi.mojo.generators;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.util.List;

import com.google.j2cl.common.FrontendUtils;
import net.cardosi.mojo.cache.TranspiledCacheEntry;
import net.cardosi.mojo.exception.GenerationException;
import org.apache.commons.io.FileUtils;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.jboss.jandex.MethodInfo;

/**
 * @author Dmitrii Tikhomirov
 * Created by treblereel 10/18/20
 */
public class EntryPointGenerator implements Generator {

    private final Indexer indexer = new Indexer();
    private final DotName GWT_ENTRY_POINT = DotName.createSimple("org.gwtproject.annotations.GwtEntryPoint");
    private final TranspiledCacheEntry path;

    public EntryPointGenerator(TranspiledCacheEntry path) {
        this.path = path;
    }

    public Generator process(List<FrontendUtils.FileInfo> sources) {
        for (FrontendUtils.FileInfo source : sources) {
            String absolutePath = path.getBytecodeDir() + "/" + source.originalPath().replace(".java", ".class");
            File file = new File(absolutePath);
            if (file.exists()) {
                try (InputStream in = new FileInputStream(file)) {
                    indexer.index(in);
                } catch (IOException e) {
                    throw new GenerationException(e);
                }
            }
        }
        return this;
    }

    @Override
    public void generate() {
        Index index = indexer.complete();
        List<AnnotationInstance> annotations = index.getAnnotations(GWT_ENTRY_POINT);
        annotations.stream().map(ann -> checkMethod(ann.target())).forEach(this::generate);
    }

    private AnnotationTarget checkMethod(AnnotationTarget target) {
        if (!target.kind().equals(AnnotationTarget.Kind.METHOD)) {
            throw new GenerationException("Only method can be annotated with org.gwtproject.annotations.GwtEntryPoint");
        }
        MethodInfo methodInfo = target.asMethod();
        if (!methodInfo.parameters().isEmpty()) {
            throw new GenerationException("Method, annotated with org.gwtproject.annotations.GwtEntryPoint, must have no params");
        }

        if (Modifier.isStatic(methodInfo.flags())) {
            throw new GenerationException("Method, annotated with org.gwtproject.annotations.GwtEntryPoint, must be static");
        }

        if (!Modifier.isPublic(methodInfo.flags())) {
            throw new GenerationException("Method, annotated with org.gwtproject.annotations.GwtEntryPoint, must be public");
        }

        if (Modifier.isAbstract(methodInfo.flags())) {
            throw new GenerationException("Method, annotated with org.gwtproject.annotations.GwtEntryPoint, must not be abstract");
        }

        if (Modifier.isNative(methodInfo.flags())) {
            throw new GenerationException("Method, annotated with org.gwtproject.annotations.GwtEntryPoint, must not be native");
        }
        ClassInfo clazz = methodInfo.declaringClass();

        if (Modifier.isAbstract(clazz.flags())) {
            throw new GenerationException("Class with method, annotated with org.gwtproject.annotations.GwtEntryPoint, must not be abstract");
        }

        if (!Modifier.isPublic(clazz.flags())) {
            throw new GenerationException("Class with method, annotated with org.gwtproject.annotations.GwtEntryPoint, must be public");
        }
        return target;
    }

    private void generate(AnnotationTarget target) {
        MethodInfo methodInfo = target.asMethod();
        String methodName = methodInfo.name();
        ClassInfo clazz = methodInfo.declaringClass();
        String className = clazz.name().local();
        String classPkg = clazz.name().prefix().toString();

        String filename = generateNativeJsFilename(className, classPkg);
        String source = generateNativeJsSource(methodName, className);

        File file = new File(filename);
        if (!file.exists()) {
            try {
                FileUtils.writeStringToFile(file, source, Charset.defaultCharset(), false);
            } catch (IOException e) {
                throw new GenerationException("Unable to write a file ", e);
            }
        }
    }

    private String generateNativeJsFilename(String className, String classPkg) {
        StringBuffer sb = new StringBuffer();
        sb.append(path.getStrippedSourcesDir());
        sb.append("/");
        sb.append(classPkg.replaceAll("\\.", "/"));
        sb.append("/");
        sb.append(className);
        sb.append(".native.js");
        return sb.toString();
    }

    private String generateNativeJsSource(String methodName, String className) {
        StringBuffer source = new StringBuffer();
        source.append("setTimeout(function(){");
        source.append(System.lineSeparator());
        source.append("var ep = ");
        source.append(className);
        source.append(".$create__();");
        source.append(System.lineSeparator());
        source.append("    ep.m_");
        source.append(methodName);
        source.append("__()");
        source.append(System.lineSeparator());
        source.append("}, 0);");
        source.append(System.lineSeparator());
        return source.toString();
    }
}