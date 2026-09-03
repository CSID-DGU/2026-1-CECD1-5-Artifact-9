import { useState } from "react";

import { searchPatientsByConditions, type Patient } from "../api/patients";
import { listVisitsByPatient, type Visit, type VisitStatus } from "../api/visits";
import { getPrescription, type PrescriptionResponse } from "../api/prescription";
import {
  CERTIFICATE_TYPES,
  draftCertificate,
  getCertificate,
  issueCertificate,
  listCertificatesByPatient,
  reissueCertificate,
  voidCertificate,
  type CertificateResponse,
  type CertificateSummary,
  type CertificateType,
} from "../api/certificates";
import { getErrorMessage, isNotFound } from "../api/errors";
import { printCertificateSlip } from "../api/print";
import { useAuth } from "../auth/AuthContext";
import { hasAtLeast } from "../auth/roles";
import { Button } from "../components/Button";
import { Card } from "../components/Card";
import { CertificateDocumentView } from "../components/CertificateDocumentView";

const STATUS_LABELS: Record<VisitStatus, string> = {
  RECEIVED: "접수",
  IN_PROGRESS: "진료중",
  ANALYZING: "분석중",
  ANALYZED: "분석완료",
  DIAGNOSED: "진단완료",
  PRESCRIBED: "처방완료",
  COMPLETED: "진료완료",
  CANCELLED: "취소",
};

/** 서술 항목이 있는 종류. 없는 종류는 AI 초안 버튼 자체를 감춘다. */
const NARRATIVE_FIELDS: Record<CertificateType, Array<keyof FormState>> = {
  PRESCRIPTION: [],
  DIAGNOSIS: ["opinion", "treatmentPlan"],
  TREATMENT_CONFIRMATION: [],
  MEDICAL_OPINION: ["opinion"],
  REFERRAL: ["referralReason", "opinion"],
};

type FormState = {
  purpose: string;
  submitTo: string;
  referralTo: string;
  opinion: string;
  treatmentPlan: string;
  referralReason: string;
  remarks: string;
  prescriptionValidDays: string;
};

const EMPTY_FORM: FormState = {
  purpose: "",
  submitTo: "",
  referralTo: "",
  opinion: "",
  treatmentPlan: "",
  referralReason: "",
  remarks: "",
  prescriptionValidDays: "",
};

/** LLM 초안 원문. 의사가 고쳤는지 판정하려면 원문을 들고 있어야 한다. */
type DraftOrigin = {
  opinion: string;
  treatmentPlan: string;
  referralReason: string;
  model: string | null;
};

export default function Certificate() {
  const { user } = useAuth();
  const isDoctor = hasAtLeast(user?.role, "DOCTOR");

  const [nameQuery, setNameQuery] = useState("");
  const [patients, setPatients] = useState<Patient[]>([]);
  const [hasSearched, setHasSearched] = useState(false);
  const [selectedPatient, setSelectedPatient] = useState<Patient | null>(null);

  const [visits, setVisits] = useState<Visit[]>([]);
  const [selectedVisit, setSelectedVisit] = useState<Visit | null>(null);
  const [prescription, setPrescription] = useState<PrescriptionResponse | null>(null);

  const [history, setHistory] = useState<CertificateSummary[]>([]);
  /** 중앙에 A4로 그려지는 발급 문서. null이면 발급 전 화면을 보여준다. */
  const [viewed, setViewed] = useState<CertificateResponse | null>(null);

  const [selectedType, setSelectedType] = useState<CertificateType | null>(null);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [draftOrigin, setDraftOrigin] = useState<DraftOrigin | null>(null);

  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const typeMeta = CERTIFICATE_TYPES.find((t) => t.type === selectedType) ?? null;
  const narrativeFields = selectedType ? NARRATIVE_FIELDS[selectedType] : [];

  function update<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  function resetIssueForm() {
    setSelectedType(null);
    setForm(EMPTY_FORM);
    setDraftOrigin(null);
  }

  async function handleSearch() {
    const name = nameQuery.trim();
    if (!name) {
      setError("환자 이름을 입력해 주세요.");
      return;
    }
    setBusy("search");
    setError(null);
    setNotice(null);
    try {
      setPatients(await searchPatientsByConditions({ name }));
      setHasSearched(true);
    } catch (e) {
      setError(getErrorMessage(e));
    } finally {
      setBusy(null);
    }
  }

  async function handleSelectPatient(patient: Patient) {
    setSelectedPatient(patient);
    setSelectedVisit(null);
    setPrescription(null);
    setViewed(null);
    resetIssueForm();
    setError(null);
    setNotice(null);

    setBusy("patient");
    try {
      const [visitList, certificateList] = await Promise.all([
        listVisitsByPatient(patient.id),
        listCertificatesByPatient(patient.id),
      ]);
      setVisits(visitList);
      setHistory(certificateList);
    } catch (e) {
      setError(getErrorMessage(e));
    } finally {
      setBusy(null);
    }
  }

  /**
   * 내원을 고르면 그 진료의 처방을 함께 읽는다. 발급 전에 "무슨 내용으로 나갈지"를
   * 확인시키기 위해서다. 처방이 없어도 오류가 아니다 — 진료확인서는 처방 없이 발급된다.
   */
  async function handleSelectVisit(visit: Visit) {
    setSelectedVisit(visit);
    setViewed(null);
    resetIssueForm();
    setError(null);
    setNotice(null);

    setBusy("visit");
    try {
      setPrescription(await getPrescription(visit.id));
    } catch (e) {
      if (isNotFound(e)) {
        setPrescription(null);
      } else {
        setError(getErrorMessage(e));
      }
    } finally {
      setBusy(null);
    }
  }

  async function handleDraft() {
    if (!selectedVisit || !selectedType) return;
    setBusy("draft");
    setError(null);
    setNotice(null);
    try {
      const result = await draftCertificate(selectedVisit.id, {
        type: selectedType,
        purpose: form.purpose || null,
        submitTo: form.submitTo || null,
        referralTo: form.referralTo || null,
      });

      if (!result.generated) {
        setDraftOrigin(null);
        setNotice(result.message ?? "AI 초안을 만들지 못했습니다. 직접 작성해 주세요.");
        return;
      }

      const origin: DraftOrigin = {
        opinion: result.opinion ?? "",
        treatmentPlan: result.treatmentPlan ?? "",
        referralReason: result.referralReason ?? "",
        model: result.model,
      };
      setDraftOrigin(origin);
      setForm((prev) => ({
        ...prev,
        opinion: origin.opinion || prev.opinion,
        treatmentPlan: origin.treatmentPlan || prev.treatmentPlan,
        referralReason: origin.referralReason || prev.referralReason,
      }));
      setNotice(result.message);
    } catch (e) {
      setError(getErrorMessage(e));
    } finally {
      setBusy(null);
    }
  }

  async function handleIssue() {
    if (!selectedVisit || !selectedType || !selectedPatient) return;
    setBusy("issue");
    setError(null);
    setNotice(null);
    try {
      const issued = await issueCertificate(selectedVisit.id, {
        type: selectedType,
        purpose: form.purpose || null,
        submitTo: form.submitTo || null,
        referralTo: form.referralTo || null,
        opinion: form.opinion || null,
        treatmentPlan: form.treatmentPlan || null,
        referralReason: form.referralReason || null,
        remarks: form.remarks || null,
        prescriptionValidDays: form.prescriptionValidDays
          ? Number(form.prescriptionValidDays)
          : null,
        aiDraft: draftOrigin ? JSON.stringify(draftOrigin) : null,
        aiModel: draftOrigin?.model ?? null,
        aiEdited: draftOrigin ? isDraftEdited(draftOrigin, form) : null,
      });

      setViewed(issued);
      setHistory(await listCertificatesByPatient(selectedPatient.id));
      resetIssueForm();
      setNotice(`${issued.typeLabel} 발급 완료 (발급번호 ${issued.serialNo ?? "-"})`);
    } catch (e) {
      setError(getErrorMessage(e));
    } finally {
      setBusy(null);
    }
  }

  async function handleOpen(summary: CertificateSummary) {
    setBusy("open");
    setError(null);
    setNotice(null);
    try {
      setViewed(await getCertificate(summary.id));
    } catch (e) {
      setError(getErrorMessage(e));
    } finally {
      setBusy(null);
    }
  }

  async function handleReissue() {
    if (!viewed || !selectedPatient) return;
    setBusy("reissue");
    setError(null);
    setNotice(null);
    try {
      const copy = await reissueCertificate(viewed.id);
      setViewed(copy);
      setHistory(await listCertificatesByPatient(selectedPatient.id));
      setNotice(`재발급 완료 (발급번호 ${copy.serialNo ?? "-"})`);
    } catch (e) {
      setError(getErrorMessage(e));
    } finally {
      setBusy(null);
    }
  }

  async function handleVoid() {
    if (!viewed || !selectedPatient) return;
    const reason = window.prompt("무효 사유를 입력하세요. (발급대장에 그대로 기록됩니다)");
    if (reason === null) return;
    if (!reason.trim()) {
      setError("무효 사유는 필수입니다.");
      return;
    }
    setBusy("void");
    setError(null);
    setNotice(null);
    try {
      const updated = await voidCertificate(viewed.id, reason.trim());
      setViewed(updated);
      setHistory(await listCertificatesByPatient(selectedPatient.id));
      setNotice("무효 처리되었습니다. 발급 기록은 대장에 그대로 남습니다.");
    } catch (e) {
      setError(getErrorMessage(e));
    } finally {
      setBusy(null);
    }
  }

  /** 종이 출력과 PDF 저장 모두 브라우저 인쇄 대화상자로 처리한다(index.css의 @media print). */
  function handlePrint() {
    window.print();
  }

  /**
   * 감열지 발급확인증 출력. 위의 handlePrint()(A4 법정 서식)와는 완전히 별개다.
   *
   * 감열지는 감열층의 발색 반응으로 글자를 만드는 종이라 열·직사광선·가소제
   * (비닐 파일, 영수증 지갑)에 닿으면 수개월 안에 글자가 사라진다. 그래서 이
   * 출력물은 법정 서식을 대체하지 못하고, 발급 사실 확인 및 환자 안내용
   * 보조 출력물로만 쓴다. 보존용 원본은 언제나 A4 쪽이다.
   */
  async function handlePrintSlip() {
    if (!viewed) return;
    setError(null);
    setNotice(null);
    setBusy("slip");
    try {
      const outcome = await printCertificateSlip(viewed.id);
      // 프린터가 꺼져 있어도 서버는 200 을 준다 — ok 플래그로 갈라서 안내한다.
      if (outcome.ok) setNotice(`발급확인증을 출력했습니다 (발급번호 ${viewed.serialNo ?? "-"})`);
      else setError(`발급확인증 출력 실패 — ${outcome.detail}`);
    } catch (e) {
      setError(getErrorMessage(e));
    } finally {
      setBusy(null);
    }
  }

  const canIssueSelectedType =
    selectedType !== null &&
    selectedVisit !== null &&
    (typeMeta?.doctorOnly ? isDoctor : true) &&
    (selectedType === "TREATMENT_CONFIRMATION" || prescription !== null);

  return (
    <div className="flex-1 p-[8px] flex gap-[8px] overflow-hidden">
      {/* ------------------------------------------------------------ */}
      {/* 좌측: 환자 → 내원 → 발급이력                                   */}
      {/* ------------------------------------------------------------ */}
      <section className="w-[300px] flex flex-col shrink-0 gap-[8px] overflow-y-auto">
        <Card title="환자 검색">
          <div className="flex gap-2">
            <input
              value={nameQuery}
              onChange={(e) => setNameQuery(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && handleSearch()}
              placeholder="환자 이름"
              className="flex-1 min-w-0 px-3 py-1.5 rounded bg-side-bg border border-gray-600 text-sm text-white focus:outline-none focus:border-blue-500"
            />
            <Button onClick={handleSearch} disabled={busy === "search"}>
              {busy === "search" ? "검색중" : "검색"}
            </Button>
          </div>

          {hasSearched && patients.length === 0 && (
            <p className="mt-3 text-xs text-gray-400">검색 결과가 없습니다.</p>
          )}

          {patients.length > 0 && (
            <ul className="mt-3 flex flex-col gap-1">
              {patients.map((p) => (
                <li key={p.id}>
                  <button
                    onClick={() => handleSelectPatient(p)}
                    className={`w-full text-left px-3 py-2 rounded border transition-colors ${
                      selectedPatient?.id === p.id
                        ? "border-blue-500 bg-blue-500/10"
                        : "border-gray-700 bg-side-bg hover:border-gray-500"
                    }`}
                  >
                    <div className="text-xs text-white font-medium">{p.name}</div>
                    <div className="text-[10px] text-gray-400">
                      {p.birthDate ?? "-"} · {genderLabel(p.gender)}
                    </div>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </Card>

        {selectedPatient && (
          <Card title="내원 이력">
            {visits.length === 0 ? (
              <p className="text-xs text-gray-400">내원 기록이 없습니다.</p>
            ) : (
              <ul className="flex flex-col gap-1">
                {visits.map((v) => (
                  <li key={v.id}>
                    <button
                      onClick={() => handleSelectVisit(v)}
                      className={`w-full text-left px-3 py-2 rounded border transition-colors ${
                        selectedVisit?.id === v.id
                          ? "border-blue-500 bg-blue-500/10"
                          : "border-gray-700 bg-side-bg hover:border-gray-500"
                      }`}
                    >
                      <div className="text-xs text-white">{formatDateTime(v.visitDate)}</div>
                      <div className="text-[10px] text-gray-400">{STATUS_LABELS[v.status]}</div>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        )}

        {selectedPatient && (
          <Card title={`발급 이력 (${history.length})`}>
            {history.length === 0 ? (
              <p className="text-xs text-gray-400">발급된 증명서가 없습니다.</p>
            ) : (
              <ul className="flex flex-col gap-1">
                {history.map((c) => (
                  <li key={c.id}>
                    <button
                      onClick={() => handleOpen(c)}
                      className={`w-full text-left px-3 py-2 rounded border transition-colors ${
                        viewed?.id === c.id
                          ? "border-blue-500 bg-blue-500/10"
                          : "border-gray-700 bg-side-bg hover:border-gray-500"
                      }`}
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span className="text-xs text-white font-medium">{c.typeLabel}</span>
                        {c.status === "VOID" ? (
                          <span className="px-1.5 py-0.5 rounded text-[10px] bg-red-500/20 text-red-300">
                            무효
                          </span>
                        ) : c.reissueOf !== null ? (
                          <span className="px-1.5 py-0.5 rounded text-[10px] bg-purple-500/20 text-purple-300">
                            재발급
                          </span>
                        ) : null}
                      </div>
                      <div className="text-[10px] text-gray-400">
                        {c.serialNo ?? "-"} · {formatDateTime(c.issuedAt)}
                      </div>
                      {c.purpose && (
                        <div className="text-[10px] text-gray-500 truncate">용도: {c.purpose}</div>
                      )}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        )}
      </section>

      {/* ------------------------------------------------------------ */}
      {/* 중앙: 문서 미리보기                                            */}
      {/* ------------------------------------------------------------ */}
      <section className="flex-1 flex flex-col gap-[8px] min-w-0">
        <Card title="미리보기" className="flex-1" contentClassName="bg-gray-600/30">
          {viewed ? (
            <CertificateDocumentView certificate={viewed} />
          ) : (
            <IssuePreview
              patient={selectedPatient}
              visit={selectedVisit}
              prescription={prescription}
              typeLabel={typeMeta?.label ?? null}
              form={form}
            />
          )}
        </Card>
      </section>

      {/* ------------------------------------------------------------ */}
      {/* 우측: 발급 / 출력                                              */}
      {/* ------------------------------------------------------------ */}
      <section className="w-[320px] flex flex-col shrink-0 gap-[8px] overflow-y-auto">
        {(error || notice) && (
          <div
            className={`px-3 py-2 rounded text-[11px] ${
              error ? "bg-red-500/15 text-red-300" : "bg-blue-500/15 text-blue-200"
            }`}
          >
            {error ?? notice}
          </div>
        )}

        <Card title="증명서 종류">
          {!selectedVisit && (
            <p className="mb-2 text-[11px] text-gray-400">
              먼저 환자와 내원 건을 선택하세요.
            </p>
          )}
          <div className="flex flex-col gap-2">
            {CERTIFICATE_TYPES.map((t) => {
              const needsPrescription = t.type !== "TREATMENT_CONFIRMATION";
              const blockedByRole = t.doctorOnly && !isDoctor;
              const blockedByData = needsPrescription && prescription === null;
              const disabled = !selectedVisit || blockedByRole || blockedByData;

              return (
                <button
                  key={t.type}
                  disabled={disabled}
                  onClick={() => {
                    setSelectedType(t.type);
                    setViewed(null);
                    setNotice(null);
                    setError(null);
                  }}
                  className={`w-full text-left px-3 py-2.5 rounded border transition-colors disabled:opacity-40 disabled:cursor-not-allowed ${
                    selectedType === t.type
                      ? "border-blue-500 bg-blue-500/10"
                      : "border-gray-700 bg-side-bg hover:border-gray-500"
                  }`}
                >
                  <div className="flex items-center gap-1.5">
                    <span className="text-xs text-white font-medium">{t.label}</span>
                    {t.statutory && (
                      <span className="px-1 py-px rounded text-[9px] bg-gray-600 text-gray-200">
                        법정
                      </span>
                    )}
                    {t.doctorOnly && (
                      <span className="px-1 py-px rounded text-[9px] bg-amber-500/20 text-amber-300">
                        의사
                      </span>
                    )}
                  </div>
                  <div className="text-[10px] text-gray-400 mt-0.5">{t.description}</div>
                  {selectedVisit && blockedByRole && (
                    <div className="text-[10px] text-amber-400 mt-1">
                      직접 진료한 의사만 발급할 수 있습니다 (의료법 제17조)
                    </div>
                  )}
                  {selectedVisit && !blockedByRole && blockedByData && (
                    <div className="text-[10px] text-amber-400 mt-1">
                      진료(처방)가 확정되어야 발급할 수 있습니다
                    </div>
                  )}
                </button>
              );
            })}
          </div>
        </Card>

        {selectedType && (
          <Card title={`${typeMeta?.label} 작성`}>
            <div className="flex flex-col gap-3">
              <Field label="용도">
                <TextInput
                  value={form.purpose}
                  onChange={(v) => update("purpose", v)}
                  placeholder="예: 보험 청구, 회사 제출"
                />
              </Field>
              <Field label="제출처">
                <TextInput
                  value={form.submitTo}
                  onChange={(v) => update("submitTo", v)}
                  placeholder="예: ○○화재, ○○기업"
                />
              </Field>

              {selectedType === "REFERRAL" && (
                <Field label="의뢰 의료기관">
                  <TextInput
                    value={form.referralTo}
                    onChange={(v) => update("referralTo", v)}
                    placeholder="예: ○○대학교병원 피부과"
                  />
                </Field>
              )}

              {selectedType === "PRESCRIPTION" && (
                <Field label="사용기간(일)">
                  <TextInput
                    value={form.prescriptionValidDays}
                    onChange={(v) => update("prescriptionValidDays", v.replace(/\D/g, ""))}
                    placeholder="미입력 시 3일"
                  />
                </Field>
              )}

              {narrativeFields.length > 0 && (
                <>
                  <div className="flex items-center justify-between">
                    <span className="text-[11px] text-gray-400">서술 항목</span>
                    <Button
                      type="secondary"
                      onClick={handleDraft}
                      disabled={busy === "draft"}
                      className="px-2 py-1 text-[11px]"
                    >
                      {busy === "draft" ? "생성중..." : "AI 초안"}
                    </Button>
                  </div>

                  {draftOrigin && (
                    <p className="text-[10px] text-amber-300 leading-relaxed">
                      AI 초안입니다. 반드시 검토·수정한 뒤 발급하세요.
                      {isDraftEdited(draftOrigin, form) && (
                        <span className="text-green-300"> (수정됨)</span>
                      )}
                    </p>
                  )}

                  {narrativeFields.includes("referralReason") && (
                    <Field label="의뢰 사유">
                      <TextArea
                        value={form.referralReason}
                        onChange={(v) => update("referralReason", v)}
                        rows={4}
                      />
                    </Field>
                  )}
                  {narrativeFields.includes("opinion") && (
                    <Field label={selectedType === "MEDICAL_OPINION" ? "소견" : "치료 소견"}>
                      <TextArea
                        value={form.opinion}
                        onChange={(v) => update("opinion", v)}
                        rows={6}
                      />
                    </Field>
                  )}
                  {narrativeFields.includes("treatmentPlan") && (
                    <Field label="향후 치료계획">
                      <TextArea
                        value={form.treatmentPlan}
                        onChange={(v) => update("treatmentPlan", v)}
                        rows={4}
                      />
                    </Field>
                  )}
                </>
              )}

              {selectedType !== "PRESCRIPTION" && (
                <Field label="비고">
                  <TextArea value={form.remarks} onChange={(v) => update("remarks", v)} rows={2} />
                </Field>
              )}

              <Button onClick={handleIssue} disabled={!canIssueSelectedType || busy === "issue"}>
                {busy === "issue" ? "발급중..." : "발급"}
              </Button>
              <p className="text-[10px] text-gray-500 leading-relaxed">
                병명·약품·날짜·면허번호는 진료기록에서 자동으로 채워집니다. 발급 시점의 내용은
                그대로 보관되며, 이후 처방이 수정되어도 발급된 서류는 바뀌지 않습니다.
              </p>
            </div>
          </Card>
        )}

        <Card title="출력">
          <div className="flex flex-col gap-2">
            <Button onClick={handlePrint} disabled={!viewed || viewed.status === "VOID"}>
              인쇄
            </Button>
            <Button
              type="secondary"
              onClick={handlePrint}
              disabled={!viewed || viewed.status === "VOID"}
            >
              PDF 저장
            </Button>
            <Button
              type="secondary"
              onClick={handlePrintSlip}
              disabled={!viewed || viewed.status === "VOID" || busy === "slip"}
            >
              {busy === "slip" ? "출력중..." : "발급확인증 인쇄"}
            </Button>
            <Button
              type="secondary"
              onClick={handleReissue}
              disabled={!viewed || viewed.status === "VOID" || busy === "reissue"}
            >
              {busy === "reissue" ? "재발급중..." : "재발급"}
            </Button>
            {isDoctor && (
              <Button
                type="secondary"
                onClick={handleVoid}
                disabled={!viewed || viewed.status === "VOID" || busy === "void"}
                className="border-red-500/60 text-red-300 hover:bg-red-500/10"
              >
                무효 처리
              </Button>
            )}
          </div>
          <p className="text-[10px] text-gray-500 mt-3 leading-relaxed">
            PDF 저장은 인쇄 대화상자에서 대상을 &lsquo;PDF로 저장&rsquo;으로 선택하세요.
            <br />
            발급확인증은 영수증 프린터로 나가는 안내용 출력물이며, 감열지 특성상
            보존용으로 쓸 수 없습니다. 원본은 A4 인쇄본입니다.
            <br />
            확인증의 QR 은 환자 생년월일을 입력해야 내용이 열립니다.
          </p>
          {viewed && (
            <div className="mt-3 pt-3 border-t border-gray-700 flex flex-col gap-1">
              <MetaRow label="발급번호" value={viewed.serialNo ?? "-"} />
              <MetaRow label="발급자" value={viewed.issuerName} />
              <MetaRow label="발급일시" value={formatDateTime(viewed.issuedAt)} />
              {viewed.reissueOf !== null && (
                <MetaRow label="원본" value={`#${viewed.reissueOf}`} />
              )}
              {viewed.aiModel && (
                <MetaRow
                  label="AI 초안"
                  value={`${viewed.aiModel}${viewed.aiEdited ? " (수정됨)" : ""}`}
                />
              )}
            </div>
          )}
        </Card>
      </section>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* 발급 전 화면 — 무슨 내용으로 나갈지 확인시킨다                        */
/* ------------------------------------------------------------------ */

function IssuePreview({
  patient,
  visit,
  prescription,
  typeLabel,
  form,
}: {
  patient: Patient | null;
  visit: Visit | null;
  prescription: PrescriptionResponse | null;
  typeLabel: string | null;
  form: FormState;
}) {
  if (!patient || !visit) {
    return (
      <div className="flex flex-col items-center justify-center h-full gap-3 py-10 text-center">
        <div className="w-16 h-16 rounded-full bg-gray-700/50 flex items-center justify-center">
          <svg
            xmlns="http://www.w3.org/2000/svg"
            width="28"
            height="28"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth="1.5"
            className="text-gray-400"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m0 12.75h7.5m-7.5 3H12M10.5 2.25H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z"
            />
          </svg>
        </div>
        <div>
          <p className="text-sm text-gray-300">발급할 진료를 선택하세요</p>
          <p className="text-xs text-gray-400 mt-1">
            왼쪽에서 환자를 검색해 내원 건을 고르거나, 발급 이력을 눌러 지난 서류를 확인할 수 있습니다.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto w-full max-w-[210mm] bg-white text-black px-[14mm] py-[12mm]">
      <div className="border-b border-gray-300 pb-2 mb-4">
        <h2 className="text-base font-bold">{typeLabel ?? "발급 예정 내용"}</h2>
        <p className="text-[11px] text-gray-600 mt-0.5">
          아직 발급되지 않았습니다. 아래 진료기록을 근거로 서류가 작성됩니다.
        </p>
      </div>

      <dl className="text-[12px] flex flex-col gap-1.5">
        <PreviewRow label="환자" value={`${patient.name} (${genderLabel(patient.gender)}, ${patient.birthDate ?? "-"})`} />
        <PreviewRow label="진료일" value={formatDateTime(visit.visitDate)} />
        <PreviewRow
          label="상병"
          value={
            prescription && prescription.diseases.length > 0
              ? prescription.diseases
                  .map((d) => `${d.kcdNameKr} (${d.kcdCode})${d.isPrimary ? " [주상병]" : ""}`)
                  .join(", ")
              : "확정된 상병 없음"
          }
        />
        <PreviewRow
          label="처방"
          value={
            prescription && prescription.details.length > 0
              ? prescription.details
                  .map((d) => `${d.medicineName}${d.dosage ? ` ${d.dosage}` : ""}`)
                  .join(" / ")
              : "처방 내역 없음"
          }
        />
        <PreviewRow label="담당의" value={prescription?.memberName ?? "-"} />
        {form.purpose && <PreviewRow label="용도" value={form.purpose} />}
        {form.submitTo && <PreviewRow label="제출처" value={form.submitTo} />}
      </dl>

      {(form.opinion || form.treatmentPlan || form.referralReason) && (
        <div className="mt-5 border-t border-gray-300 pt-3 flex flex-col gap-3 text-[12px]">
          {form.referralReason && <PreviewBlock label="의뢰 사유" value={form.referralReason} />}
          {form.opinion && <PreviewBlock label="소견" value={form.opinion} />}
          {form.treatmentPlan && <PreviewBlock label="향후 치료계획" value={form.treatmentPlan} />}
        </div>
      )}
    </div>
  );
}

function PreviewRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex gap-3">
      <dt className="w-16 shrink-0 text-gray-600">{label}</dt>
      <dd className="flex-1">{value}</dd>
    </div>
  );
}

function PreviewBlock({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-gray-600 mb-1">{label}</div>
      <div className="whitespace-pre-wrap leading-relaxed">{value}</div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* 입력 조각                                                            */
/* ------------------------------------------------------------------ */

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-[11px] text-gray-400">{label}</span>
      {children}
    </label>
  );
}

function TextInput({
  value,
  onChange,
  placeholder,
}: {
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
}) {
  return (
    <input
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={placeholder}
      className="w-full px-3 py-1.5 rounded bg-side-bg border border-gray-600 text-xs text-white focus:outline-none focus:border-blue-500"
    />
  );
}

function TextArea({
  value,
  onChange,
  rows = 4,
}: {
  value: string;
  onChange: (v: string) => void;
  rows?: number;
}) {
  return (
    <textarea
      value={value}
      onChange={(e) => onChange(e.target.value)}
      rows={rows}
      className="w-full px-3 py-2 rounded bg-side-bg border border-gray-600 text-xs text-white leading-relaxed resize-y focus:outline-none focus:border-blue-500"
    />
  );
}

function MetaRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex gap-2">
      <span className="w-[52px] shrink-0 text-[10px] text-gray-500">{label}</span>
      <span className="text-[10px] text-gray-300 break-all">{value}</span>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* 헬퍼                                                                */
/* ------------------------------------------------------------------ */

/**
 * 의사가 AI 초안을 고쳤는지 판정한다.
 * 이 값은 발급 기록에 남아, 나중에 "이 문장을 누가 썼는가"를 구분할 수 있게 한다.
 */
function isDraftEdited(origin: DraftOrigin, form: FormState): boolean {
  return (
    origin.opinion !== form.opinion ||
    origin.treatmentPlan !== form.treatmentPlan ||
    origin.referralReason !== form.referralReason
  );
}

function genderLabel(gender?: string | null): string {
  if (!gender) return "-";
  const map: Record<string, string> = {
    M: "남",
    F: "여",
    MALE: "남",
    FEMALE: "여",
    OTHER: "기타",
  };
  return map[gender] ?? gender;
}

function formatDateTime(value?: string | null): string {
  if (!value) return "-";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleString("ko-KR", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}
