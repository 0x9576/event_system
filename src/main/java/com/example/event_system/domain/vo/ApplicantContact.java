package com.example.event_system.domain.vo;

import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// 개인 민감정보 따로보관
public class ApplicantContact {

    private String phoneNumber;

    private String email;

    private String address;

    // 비즈니스 편의 메서드 (예: 정보가 하나라도 있는지 확인)
    public boolean isEmpty() {
        return phoneNumber == null && email == null && address == null;
    }
}