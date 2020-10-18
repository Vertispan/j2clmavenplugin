package net.cardosi.mojo.generators;

import java.util.List;

import com.google.j2cl.common.FrontendUtils;

/**
 * @author Dmitrii Tikhomirov
 * Created by treblereel 10/18/20
 */
public interface Generator {

    Generator process(List<FrontendUtils.FileInfo> sources);

    void generate();
}
