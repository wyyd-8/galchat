package com.me.galchat.controller;

import com.me.galchat.domain.Result;
import com.me.galchat.domain.dto.ModelApiSaveDTO;
import com.me.galchat.exception.UserRequestException;
import com.me.galchat.modelapi.ModelApiService;
import com.me.galchat.utils.CurrentHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/model-apis")
public class ModelApiController {

    private final ModelApiService service;

    public ModelApiController(ModelApiService service) {
        this.service = service;
    }

    @GetMapping
    public Result list() {
        return Result.success(service.list(currentUserId()));
    }

    @PostMapping
    public Result create(@RequestBody ModelApiSaveDTO dto) {
        return Result.success(service.create(currentUserId(), dto));
    }

    @PutMapping("/{id}")
    public Result update(@PathVariable Long id,
                         @RequestBody ModelApiSaveDTO dto) {
        return Result.success(service.update(currentUserId(), id, dto));
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Long id) {
        service.delete(currentUserId(), id);
        return Result.success();
    }

    @PostMapping("/{id}/test")
    public Result test(@PathVariable Long id) {
        return Result.success(service.test(currentUserId(), id));
    }

    private Long currentUserId() {
        Integer userId = CurrentHolder.getCurrentId();
        if (userId == null) {
            throw new UserRequestException("用户未登录");
        }
        return userId.longValue();
    }
}
