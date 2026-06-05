package com.semantyca.jesoos.service.chat.ots;

import com.semantyca.jesoos.service.ScriptService;
import com.semantyca.mixpla.model.cnst.SceneTimingMode;
import com.semantyca.mixpla.model.filter.ScriptFilter;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class OtsScriptsProvider {

    @Inject
    ScriptService scriptService;

    public Uni<String> buildScriptsText() {
        ScriptFilter filter = new ScriptFilter();
        filter.setTimingMode(SceneTimingMode.RELATIVE_TO_STREAM_START);
        return scriptService.getAll(50, 0, filter).map(scripts -> {
            if (scripts == null || scripts.isEmpty()) {
                return "none available";
            }
            StringBuilder sb = new StringBuilder();
            scripts.forEach(script -> {
                sb.append("- ").append(script.getName())
                        .append(" (id: ").append(script.getId()).append(")");
                if (script.getRequiredVariables() != null && !script.getRequiredVariables().isEmpty()) {
                    sb.append(" — variables: ");
                    script.getRequiredVariables().forEach(v ->
                            sb.append(v.getName()).append("=").append(v.getDescription()).append(", "));
                    sb.setLength(sb.length() - 2);
                } else {
                    sb.append(" — no variables needed");
                }
                sb.append("\n");
            });
            return sb.toString().trim();
        });
    }
}
