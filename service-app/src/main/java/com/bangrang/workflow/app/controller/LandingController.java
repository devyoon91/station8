package com.bangrang.workflow.app.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 루트 ``/`` 랜딩 페이지. DESIGN.md의 hero stripe gradient를 페이지당 1회만 사용한다는 원칙에 따라
 * 본 페이지에만 hero를 두고 다른 운영 페이지(dashboard 등)는 깔끔한 다크 카드 캔버스를 유지.
 */
@Controller
public class LandingController {

    @GetMapping("/")
    public String landing() {
        return "landing";
    }
}
