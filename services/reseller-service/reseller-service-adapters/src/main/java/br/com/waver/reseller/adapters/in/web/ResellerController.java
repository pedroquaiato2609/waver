package br.com.waver.reseller.adapters.in.web;

import br.com.waver.kernel.party.TaxId;
import br.com.waver.kernel.tenancy.OrgId;
import br.com.waver.platform.tenancy.TenantContextHolder;
import br.com.waver.reseller.adapters.workflow.ResellerOnboardingWorkflow;
import br.com.waver.reseller.application.ResellerOnboardingService;
import br.com.waver.reseller.domain.ChecklistItem;
import br.com.waver.reseller.domain.OnboardingStatus;
import br.com.waver.reseller.domain.ResellerProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal service API (RFC-0017 §2): /v1/resellers. */
@RestController
@RequestMapping("/v1/resellers")
public class ResellerController {

    public record ContactInput(String name, String email, String phone) {
    }

    public record RegisterLeadRequest(String legalName, String tradeName, @NotNull TaxId.Type taxIdType, String taxId,
                                      @NotNull @Valid ContactInput owner, @NotNull @Valid ContactInput commercialContact) {
    }

    public record ChecklistRequest(@NotNull ChecklistItem item, String evidenceRef) {
    }

    public enum DecisionOutcome { APPROVE, REJECT }

    public record DecisionRequest(@NotNull DecisionOutcome decision, String reason) {
    }

    public record ContactView(String type, String name, String email, String phone) {
    }

    public record ChecklistItemView(String item, boolean automatic, boolean requiredForActivation, boolean done,
                                    Instant doneAt, String doneBy, String evidenceRef) {
    }

    public record ResellerView(UUID id, String legalName, String tradeName, String taxIdType, String taxId,
                               String onboardingStatus, UUID resellerOrgId, List<ContactView> contacts,
                               List<ChecklistItemView> checklist, String decidedBy, Instant decidedAt, String decisionReason,
                               String workflowId, long version, Instant createdAt) {

        static ResellerView from(ResellerProfile p, Set<ChecklistItem> required) {
            List<ChecklistItemView> checklist = Arrays.stream(ChecklistItem.values()).map(item -> {
                ResellerProfile.ChecklistEntry e = p.checklist().get(item);
                return new ChecklistItemView(item.name(), item.automatic(), required.contains(item), e != null,
                        e == null ? null : e.doneAt(), e == null ? null : e.doneBy(), e == null ? null : e.evidenceRef());
            }).toList();
            return new ResellerView(p.id(), p.legalName(), p.tradeName().orElse(null), p.taxId().type().name(),
                    p.taxId().formatted(), p.status().name(), p.resellerOrgId().map(OrgId::value).orElse(null),
                    p.contacts().stream().map(c -> new ContactView(c.type().name(), c.name(), c.email(), c.phone())).toList(),
                    checklist,
                    p.decision().map(ResellerProfile.Decision::decidedBy).orElse(null),
                    p.decision().map(ResellerProfile.Decision::decidedAt).orElse(null),
                    p.decision().map(ResellerProfile.Decision::reason).orElse(null),
                    ResellerOnboardingWorkflow.workflowId(p.id()), p.version(), p.createdAt());
        }
    }

    /** What WAVER Partner shows the reseller about itself: no internal decision data (RFC-0017 §1). */
    public record OwnProfileView(UUID id, String legalName, String tradeName, String taxId, String onboardingStatus,
                                 UUID resellerOrgId, List<ContactView> contacts) {
    }

    public record Page<T>(List<T> items, String nextCursor) {
    }

    private final ResellerOnboardingService onboarding;

    public ResellerController(ResellerOnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    @PostMapping
    public ResponseEntity<ResellerView> register(@Valid @RequestBody RegisterLeadRequest r) {
        ResellerProfile p = onboarding.registerLead(TenantContextHolder.require(), new ResellerOnboardingService.RegisterLead(
                r.legalName(), r.tradeName(), r.taxIdType(), r.taxId(),
                r.owner().name(), r.owner().email(),
                r.commercialContact().name(), r.commercialContact().email(), r.commercialContact().phone()));
        return ResponseEntity.created(URI.create("/v1/resellers/" + p.id())).body(view(p));
    }

    @GetMapping
    public Page<ResellerView> list(@RequestParam(required = false) OnboardingStatus status,
                                   @RequestParam(defaultValue = "50") int limit,
                                   @RequestParam(required = false) UUID cursor) {
        List<ResellerProfile> items = onboarding.list(TenantContextHolder.require(), status, cursor, limit);
        String next = items.size() == Math.clamp(limit, 1, 200) ? items.getLast().id().toString() : null;
        return new Page<>(items.stream().map(this::view).toList(), next);
    }

    /** The caller's own reseller (WAVER Partner). Declared before /{id} so "me" is never read as an ID. */
    @GetMapping("/me")
    public OwnProfileView mine() {
        ResellerProfile p = onboarding.mine(TenantContextHolder.require());
        return new OwnProfileView(p.id(), p.legalName(), p.tradeName().orElse(null), p.taxId().formatted(), p.status().name(),
                p.resellerOrgId().map(OrgId::value).orElse(null),
                p.contacts().stream().map(c -> new ContactView(c.type().name(), c.name(), c.email(), c.phone())).toList());
    }

    @GetMapping("/{id}")
    public ResellerView get(@PathVariable UUID id) {
        return view(onboarding.get(TenantContextHolder.require(), id));
    }

    @PostMapping("/{id}/review")
    public ResellerView startReview(@PathVariable UUID id) {
        return view(onboarding.startReview(TenantContextHolder.require(), id));
    }

    @PutMapping("/{id}/onboarding-checklist")
    public ResellerView completeItem(@PathVariable UUID id, @Valid @RequestBody ChecklistRequest r) {
        return view(onboarding.completeChecklistItem(TenantContextHolder.require(), id, r.item(), r.evidenceRef()));
    }

    /** Interim: direct decision by WAVER Commercial. Replaced by /approval-requests once approval-service exists. */
    @PostMapping("/{id}/approval-decision")
    public ResellerView decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest r) {
        return view(onboarding.decide(TenantContextHolder.require(), id, r.decision() == DecisionOutcome.APPROVE, r.reason()));
    }

    @PostMapping("/{id}/activation")
    public ResellerView activate(@PathVariable UUID id) {
        return view(onboarding.activate(TenantContextHolder.require(), id));
    }

    private ResellerView view(ResellerProfile p) {
        return ResellerView.from(p, onboarding.activationRequires());
    }
}
