package com.semantyca.djinn.service;

import com.semantyca.core.model.ScriptVariable;
import com.semantyca.core.model.cnst.LanguageTag;
import com.semantyca.djinn.dto.SceneDTO;
import com.semantyca.djinn.dto.ScenePromptDTO;
import com.semantyca.djinn.dto.ScriptDTO;
import com.semantyca.djinn.dto.ScriptFilterDTO;
import com.semantyca.djinn.dto.stream.BrandScriptDTO;
import com.semantyca.djinn.repository.ScriptRepository;
import com.semantyca.djinn.util.ScriptVariableExtractor;
import com.semantyca.mixpla.model.BrandScript;
import com.semantyca.mixpla.model.Draft;
import com.semantyca.mixpla.model.Scene;
import com.semantyca.mixpla.model.Script;
import com.semantyca.mixpla.model.cnst.SceneTimingMode;
import com.semantyca.mixpla.model.filter.ScriptFilter;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.model.user.SuperUser;
import io.kneo.core.service.AbstractService;
import io.kneo.core.service.UserService;
import io.kneo.core.util.WebHelper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ScriptService extends AbstractService<Script, ScriptDTO> {
    private final ScriptRepository repository;
    private final SceneService scriptSceneService;
    private final PromptService promptService;
    private final DraftService draftService;

    protected ScriptService() {
        super();
        this.repository = null;
        this.scriptSceneService = null;
        this.promptService = null;
        this.draftService = null;
    }

    @Inject
    public ScriptService(UserService userService, ScriptRepository repository, SceneService scriptSceneService, PromptService promptService, DraftService draftService) {
        super(userService);
        this.repository = repository;
        this.scriptSceneService = scriptSceneService;
        this.promptService = promptService;
        this.draftService = draftService;
    }

    public Uni<List<ScriptDTO>> getAllDTO(final int limit, final int offset, final IUser user) {
        return getAllDTO(limit, offset, user, null);
    }

    public Uni<List<ScriptDTO>> getAllDTO(final int limit, final int offset, final IUser user, final ScriptFilterDTO filterDTO) {
        assert repository != null;
        ScriptFilter filter = toFilter(filterDTO);
        return repository.getAll(limit, offset, false, user, filter)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    List<Uni<ScriptDTO>> unis = list.stream()
                            .map(script -> mapToDTO(script, user))
                            .collect(Collectors.toList());
                    return Uni.join().all(unis).andFailFast();
                });
    }

    private ScriptFilter toFilter(ScriptFilterDTO dto) {
        if (dto == null) {
            return null;
        }

        ScriptFilter filter = new ScriptFilter();
        filter.setActivated(dto.isActivated());
        filter.setLabels(dto.getLabels());
        filter.setTimingMode(dto.getTimingMode());
        filter.setLanguageTag(dto.getLanguageTag());
        filter.setSearchTerm(dto.getSearchTerm());

        return filter;
    }

    public Uni<Integer> getAllCount(final IUser user) {
        return getAllCount(user, null);
    }

    public Uni<Integer> getAllCount(final IUser user, final ScriptFilterDTO filterDTO) {
        assert repository != null;
        ScriptFilter filter = toFilter(filterDTO);
        return repository.getAllCount(user, false, filter);
    }

    public Uni<List<ScriptDTO>> getAllShared(final int limit, final int offset, final IUser user) {
        ScriptFilterDTO filterDTO = new ScriptFilterDTO();
        filterDTO.setTimingMode(SceneTimingMode.RELATIVE_TO_STREAM_START);
        return getAllShared(limit, offset, user, filterDTO);
    }

    public Uni<List<ScriptDTO>> getAllShared(final int limit, final int offset, final IUser user, final ScriptFilterDTO filterDTO) {
        assert repository != null;
        ScriptFilter filter = toFilter(filterDTO);
        return repository.getAllShared(limit, offset, user, filter)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    List<Uni<ScriptDTO>> unis = list.stream()
                            .map(script -> mapToDTO(script, user))
                            .collect(Collectors.toList());
                    return Uni.join().all(unis).andFailFast();
                });
    }

    public Uni<Integer> getAllSharedCount(final IUser user) {
        return getAllSharedCount(user, null);
    }

    public Uni<Integer> getAllSharedCount(final IUser user, final ScriptFilterDTO filterDTO) {
        assert repository != null;
        ScriptFilter filter = toFilter(filterDTO);
        return repository.getAllSharedCount(user, filter);
    }

    @Override
    public Uni<ScriptDTO> getDTO(UUID id, IUser user, LanguageCode language) {
        assert repository != null;
        return repository.findById(id, user, false).chain(script -> mapToDTO(script, user));
    }

    public Uni<Script> getById(UUID id, IUser user) {
        assert repository != null;
        return repository.findById(id, user, false);
    }

    public Uni<Integer> archive(String id, IUser user) {
        assert repository != null;
        return repository.archive(UUID.fromString(id), user);
    }

    @Override
    public Uni<Integer> delete(String id, IUser user) {
        assert repository != null;
        return repository.delete(UUID.fromString(id), user);
    }

    private Uni<ScriptDTO> mapToDTO(Script script, IUser user) {
        return Uni.combine().all().unis(
                userService.getUserName(script.getAuthor()),
                userService.getUserName(script.getLastModifier())
        ).asTuple().map(tuple -> {
            ScriptDTO dto = new ScriptDTO();
            dto.setId(script.getId());
            dto.setAuthor(tuple.getItem1());
            dto.setRegDate(script.getRegDate());
            dto.setLastModifier(tuple.getItem2());
            dto.setLastModifiedDate(script.getLastModifiedDate());
            dto.setName(script.getName());
            dto.setSlugName(script.getSlugName());
            dto.setDefaultProfileId(script.getDefaultProfileId());
            dto.setDescription(script.getDescription());
            dto.setAccessLevel(script.getAccessLevel());
            dto.setLanguageTag(script.getLanguageTag().tag());
            dto.setLabels(script.getLabels());
            dto.setBrands(script.getBrands());
            dto.setTimingMode(script.getTimingMode().name());
            dto.setRequiredVariables(script.getRequiredVariables());
            return dto;
        });
    }

    private Script buildEntity(ScriptDTO dto) {
        Script entity = new Script();
        entity.setName(dto.getName());
        entity.setSlugName(WebHelper.generateSlug(dto.getName()));
        entity.setDefaultProfileId(dto.getDefaultProfileId());
        entity.setDescription(dto.getDescription());
        entity.setLanguageTag(LanguageTag.fromTag(dto.getLanguageTag()));
        entity.setTimingMode(SceneTimingMode.valueOf(dto.getTimingMode()));
        entity.setLabels(dto.getLabels());
        entity.setBrands(dto.getBrands());
        return entity;
    }

    private Uni<List<ScriptVariable>> extractRequiredVariables(List<SceneDTO> scenes) {
        if (scenes == null || scenes.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        List<UUID> draftIds = scenes.stream()
                .filter(scene -> scene.getPrompts() != null)
                .flatMap(scene -> scene.getPrompts().stream())
                .map(ScenePromptDTO::getPromptId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (draftIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        List<Uni<Draft>> draftUnis = draftIds.stream()
                .map(promptId -> {
                    assert promptService != null;
                    return promptService.getById(promptId, SuperUser.build())
                            .chain(prompt -> {
                                if (prompt.getDraftId() == null) {
                                    return Uni.createFrom().nullItem();
                                }
                                assert draftService != null;
                                return draftService.getById(prompt.getDraftId(), SuperUser.build());
                            })
                            .onFailure().recoverWithNull();
                })
                .collect(Collectors.toList());

        return Uni.join().all(draftUnis).andFailFast()
                .map(drafts -> {
                    Map<String, ScriptVariable> aggregated = new LinkedHashMap<>();
                    for (Draft draft : drafts) {
                        if (draft != null && draft.getContent() != null) {
                            List<ScriptVariable> vars = ScriptVariableExtractor.extract(draft.getContent());
                            for (ScriptVariable var : vars) {
                                aggregated.putIfAbsent(var.getName(), var);
                            }
                        }
                    }
                    return new ArrayList<>(aggregated.values());
                });
    }


    public Uni<List<BrandScript>> getAllScriptsForBrandWithScenes(UUID brandId, IUser user) {
        assert repository != null;
        return repository.findForBrand(brandId, 100, 0, false, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    List<Uni<BrandScript>> unis = list.stream()
                            .map(brandScript -> populateScenesWithPrompts(brandScript, user))
                            .collect(Collectors.toList());
                    return Uni.join().all(unis).andFailFast();
                });
    }

    public Uni<List<BrandScriptDTO>> getForBrand(UUID brandId, final int limit, final int offset, IUser user) {
        assert repository != null;
        return repository.findForBrand(brandId, limit, offset, false, user)
                .chain(list -> {
                    if (list.isEmpty()) {
                        return Uni.createFrom().item(List.of());
                    }
                    List<Uni<BrandScriptDTO>> unis = list.stream()
                            .map(brandScript -> mapToDTO(brandScript, user))
                            .collect(Collectors.toList());
                    return Uni.join().all(unis).andFailFast();
                });
    }

    public Uni<Integer> getForBrandCount(UUID brandId, IUser user) {
        assert repository != null;
        return repository.findForBrandCount(brandId, false, user);
    }

    private Uni<BrandScript> populateScenesWithPrompts(BrandScript brandScript, IUser user) {
        assert scriptSceneService != null;
        return scriptSceneService.getAllWithPromptIds(brandScript.getScript().getId(), 100, 0, user)
                .map(list -> {
                    brandScript.getScript().setScenes(
                            new TreeSet<>(
                                    Comparator.comparingInt(Scene::getSeqNum)
                                            .thenComparing(Scene::getId)
                            ) {{
                                addAll(list);
                            }}
                    );
                    return brandScript;
                });
    }


    private Uni<BrandScriptDTO> mapToDTO(BrandScript brandScript, IUser user) {
        return mapToDTO(brandScript.getScript(), user).map(scriptDTO -> {
            BrandScriptDTO dto = new BrandScriptDTO();
            dto.setId(brandScript.getId());
            dto.setDefaultBrandId(brandScript.getDefaultBrandId());
            dto.setRank(brandScript.getRank());
            dto.setActive(brandScript.isActive());
            dto.setScript(scriptDTO);
            dto.setRepresentedInBrands(brandScript.getRepresentedInBrands());
            return dto;
        });
    }
}
