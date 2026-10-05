package com.company.scheduler.shared.contract;
import java.util.List;
import java.util.UUID;
public interface SkillDirectory {
    List<String> activeCodes(UUID companyId);
}
