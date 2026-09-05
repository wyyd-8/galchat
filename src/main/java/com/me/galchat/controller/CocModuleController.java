package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.domain.dto.CocModuleArchiveDTO;
import com.me.galchat.domain.dto.CocModuleContentUpdateDTO;
import com.me.galchat.domain.dto.CocModuleCreateDTO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.service.impl.trpg.CocModuleRuntimeService;
import com.me.galchat.service.impl.trpg.CocModuleService;
import com.me.galchat.utils.CurrentHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/coc-modules")
@RequiredArgsConstructor
public class CocModuleController {

    private final CocModuleService moduleService;
    private final CocModuleRuntimeService runtimeService;

    @GetMapping
    public Result list() {
        return Result.success(moduleService.listVisible(currentUserId()));
    }

    @GetMapping("/{id}")
    public Result detail(@PathVariable Long id) {
        return Result.success(moduleService.getVisible(currentUserId(), id));
    }

    @GetMapping("/mine")
    public Result mine() {
        return Result.success(moduleService.listOwned(currentUserId()));
    }

    @GetMapping("/{id}/manage")
    public Result manage(@PathVariable Long id) {
        return Result.success(moduleService.getReadableDetail(
                currentUserId(), id));
    }

    @PostMapping
    public Result create(@RequestBody CocModuleCreateDTO request) {
        return Result.success(moduleService.createOwned(
                currentUserId(), request));
    }

    @PutMapping("/{id}")
    public Result update(@PathVariable Long id,
                         @RequestBody CocModuleCreateDTO request) {
        return Result.success(moduleService.updateOwned(
                currentUserId(), id, request));
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Long id) {
        moduleService.deleteOwned(currentUserId(), id);
        return Result.success();
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<CocModuleArchiveDTO> exportModule(
            @PathVariable Long id) {
        CocModuleArchiveDTO archive = moduleService.exportReadable(
                currentUserId(), id);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("galchat-coc-module-" + id + ".json")
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        disposition.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(archive);
    }

    @PostMapping("/import")
    public Result importModule(@RequestBody CocModuleArchiveDTO archive) {
        return Result.success(moduleService.importOwned(
                currentUserId(), archive));
    }

    @PutMapping("/{moduleId}/locations/{locationId}/content")
    public Result updateLocationContent(
            @PathVariable Long moduleId,
            @PathVariable Long locationId,
            @RequestBody CocModuleContentUpdateDTO request) {
        return Result.success(moduleService.updateLocationContent(
                currentUserId(), moduleId, locationId,
                request == null ? null : request.getContent()));
    }

    @PostMapping("/{moduleId}/clues")
    public Result addClue(@PathVariable Long moduleId,
                          @RequestBody CocModuleCreateDTO.Clue request) {
        return Result.success(moduleService.addClue(
                currentUserId(), moduleId, request));
    }

    @PutMapping("/{moduleId}/clues/{clueId}/content")
    public Result updateClueContent(
            @PathVariable Long moduleId,
            @PathVariable Long clueId,
            @RequestBody CocModuleContentUpdateDTO request) {
        return Result.success(moduleService.updateClueContent(
                currentUserId(), moduleId, clueId,
                request == null ? null : request.getContent()));
    }

    @PostMapping("/{id}/unlock")
    public Result unlock(@PathVariable Long id) {
        runtimeService.unlock(currentUserId(), id);
        return Result.success();
    }

    private Long currentUserId() {
        Integer userId = CurrentHolder.getCurrentId();
        if (userId == null) {
            throw new UserRequestException("用户未登录");
        }
        return Long.valueOf(userId);
    }
}
