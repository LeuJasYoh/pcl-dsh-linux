package com.hdsl.server.api;

import com.hdsl.server.account.AccountApiService;
import com.hdsl.server.security.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 账户接口（方案 §4.2）：提供商预设、账户列表、创建/编辑/删除。
 * 所有响应均为公开元数据，永不包含 API Key 或密文；写操作需要登录 + CSRF，忙时 409。
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountApiService accounts;

    public AccountController(AccountApiService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/providers")
    public List<ApiDtos.ProviderDto> providers() {
        return accounts.providers();
    }

    /**
     * 账户列表。资料损坏时仍返回 200 + 空数组（不 500），并通过
     * {@code X-PCL-Warning: ACCOUNTS_UNREADABLE} 提示前端；/api/state 的 warnings 字段
     * 带可展示中文提示。
     */
    @GetMapping
    public ResponseEntity<List<ApiDtos.AccountDto>> list(HttpServletRequest request) {
        AccountApiService.AccountList result = accounts.list(ClientIp.of(request));
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (result.degraded()) {
            builder.header("X-PCL-Warning", AccountApiService.DEGRADED_WARNING);
        }
        return builder.body(result.accounts());
    }

    @PostMapping
    public ResponseEntity<ApiDtos.AccountDto> create(
            @RequestBody(required = false) ApiDtos.AccountSaveRequest body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accounts.create(body, ClientIp.of(request)));
    }

    @PutMapping("/{id}")
    public ApiDtos.AccountDto update(@PathVariable("id") String id,
                                     @RequestBody(required = false) ApiDtos.AccountSaveRequest body,
                                     HttpServletRequest request) {
        return accounts.update(id, body, ClientIp.of(request));
    }

    @DeleteMapping("/{id}")
    public ApiDtos.AccountDeletedDto delete(@PathVariable("id") String id, HttpServletRequest request) {
        return new ApiDtos.AccountDeletedDto(accounts.delete(id, ClientIp.of(request)));
    }
}
