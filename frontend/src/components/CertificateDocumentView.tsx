import type {
  CertificateDocument,
  CertificateResponse,
  DiseaseLine,
} from "../api/certificates";

/**
 * 이 컴포넌트가 실제로 쓰는 필드만 추린 것.
 *
 * 발급 화면(Certificate.tsx)은 `CertificateResponse` 를 그대로 넘기고, 감열지 QR 로
 * 들어오는 공개 열람 페이지는 내부 ID 가 빠진 축소 응답을 넘긴다. 둘 다 받으려고
 * 프로퍼티를 넓혀 둔 것이지, 발급 화면 쪽 동작은 아무것도 바뀌지 않는다.
 *
 * 재발급 표시만 두 형태를 받는다. 발급 화면은 원본 증명서 ID(`reissueOf`)를 들고
 * 있지만, 공개 응답에는 다른 증명서의 내부 ID 를 싣지 않아 boolean 만 온다.
 * 화면이 쓰는 것은 "재발급인가" 하나뿐이라 여기서 흡수한다.
 */
export type CertificateDocumentViewData = Pick<
  CertificateResponse,
  "type" | "typeLabel" | "status" | "voidReason" | "content"
> & {
  reissueOf?: number | null;
  reissued?: boolean;
};

/**
 * 발급된 증명서를 A4 한 장으로 그린다.
 *
 * 그리는 재료는 `certificate.content` 스냅샷뿐이다. 지금 DB에 있는 처방이나 환자 정보를
 * 다시 읽지 않는다 — 발급 후 처방이 수정되어도 이미 나간 종이와 화면이 같아야 하기 때문이다.
 *
 * 인쇄 대상 영역을 `id="certificate-print-area"`로 표시한다. 인쇄 CSS(index.css)가
 * 이 id 하나만 남기고 나머지 화면을 숨기므로, 종이 출력과 PDF 저장이 같은 경로로 처리된다.
 */
export function CertificateDocumentView({
  certificate,
}: {
  certificate: CertificateDocumentViewData;
}) {
  const doc = certificate.content;
  const voided = certificate.status === "VOID";
  // reissueOf 가 아예 없는 경우(공개 응답)와 null 인 경우(최초 발급)를 함께 걸러야 한다.
  const reissued = certificate.reissued ?? certificate.reissueOf != null;

  return (
    <div
      id="certificate-print-area"
      className="cert-page bg-white text-black mx-auto w-full max-w-[210mm] px-[14mm] py-[12mm] font-serif"
    >
      {voided && (
        <div className="mb-4 border-2 border-red-600 px-3 py-2 text-center">
          <div className="text-lg font-bold text-red-600">무 효</div>
          <div className="text-[11px] text-red-700">
            {certificate.voidReason ?? "무효 처리된 증명서입니다."}
          </div>
        </div>
      )}

      <header className="mb-5 text-center">
        <h1 className="text-2xl font-bold tracking-[0.4em]">{certificate.typeLabel}</h1>
        <div className="mt-2 text-right text-[11px]">
          발급번호: {doc.serialNo ?? "-"}
          {reissued && <span className="ml-2 font-bold">[재발급]</span>}
        </div>
      </header>

      <PatientTable doc={doc} type={certificate.type} />

      <div className="mt-3">
        {certificate.type === "PRESCRIPTION" && <PrescriptionBody doc={doc} />}
        {certificate.type === "DIAGNOSIS" && <DiagnosisBody doc={doc} />}
        {certificate.type === "TREATMENT_CONFIRMATION" && <TreatmentConfirmationBody doc={doc} />}
        {certificate.type === "MEDICAL_OPINION" && <MedicalOpinionBody doc={doc} />}
        {certificate.type === "REFERRAL" && <ReferralBody doc={doc} />}
      </div>

      <Footer doc={doc} type={certificate.type} />
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* 종류별 본문                                                          */
/* ------------------------------------------------------------------ */

/** 진단서 — 의료법 시행규칙 별지 제5호의2서식의 기재 항목을 따른다. */
function DiagnosisBody({ doc }: { doc: CertificateDocument }) {
  return (
    <Table>
      <Row label="병명" wide>
        <DiseaseList diseases={doc.diseases} />
      </Row>
      <Row label="발병일">{formatKoreanDate(doc.visitDate)}</Row>
      <Row label="진단일">{formatKoreanDate(doc.visitDate)}</Row>
      <Row label="치료 내용 및 향후 치료에 대한 소견" wide>
        <MultilineText value={doc.opinion} minHeight="26mm" />
      </Row>
      <Row label="향후 치료계획" wide>
        <MultilineText value={doc.treatmentPlan} minHeight="18mm" />
      </Row>
      <Row label="용도">{doc.purpose ?? "-"}</Row>
      {doc.submitTo && <Row label="제출처">{doc.submitTo}</Row>}
      {doc.remarks && (
        <Row label="비고" wide>
          <MultilineText value={doc.remarks} />
        </Row>
      )}
    </Table>
  );
}

/** 처방전 — 의료법 시행규칙 별지 제9호서식. 약품 표가 본문이다. */
function PrescriptionBody({ doc }: { doc: CertificateDocument }) {
  const drugs = doc.drugs ?? [];
  return (
    <>
      <Table>
        <Row label="질병분류기호" wide>
          {(doc.diseases ?? []).map((d) => d.code).join(", ") || "-"}
        </Row>
      </Table>

      <table className="mt-3 w-full border-collapse border border-black text-[12px]">
        <thead>
          <tr className="bg-gray-100">
            <th className="border border-black px-2 py-1 text-left">처방 의약품의 명칭</th>
            <th className="w-[70px] border border-black px-2 py-1">1회 투약량</th>
            <th className="w-[70px] border border-black px-2 py-1">투약일수</th>
            <th className="w-[110px] border border-black px-2 py-1">용법</th>
          </tr>
        </thead>
        <tbody>
          {drugs.length === 0 ? (
            <tr>
              <td className="border border-black px-2 py-3 text-center" colSpan={4}>
                처방 내역 없음
              </td>
            </tr>
          ) : (
            drugs.map((drug, i) => (
              <tr key={`${drug.name}-${i}`}>
                <td className="border border-black px-2 py-1">{drug.name}</td>
                <td className="border border-black px-2 py-1 text-center">{drug.dosage ?? "-"}</td>
                <td className="border border-black px-2 py-1 text-center">
                  {drug.durationDays ?? "-"}
                </td>
                <td className="border border-black px-2 py-1">{drug.notes ?? "-"}</td>
              </tr>
            ))
          )}
        </tbody>
      </table>

      <Table className="mt-3">
        <Row label="사용기간">
          발급일부터 {doc.prescriptionValidDays ?? 3}일간
          <span className="ml-2 text-[10px] text-gray-600">
            (사용기간 내에 약국에 제출해야 합니다)
          </span>
        </Row>
      </Table>
    </>
  );
}

/**
 * 진료확인서 — 비법정서식.
 * 병명이 들어가지 않는다. 그래서 원무과가 발급할 수 있고, 회사 제출용으로 주로 쓰인다.
 */
function TreatmentConfirmationBody({ doc }: { doc: CertificateDocument }) {
  return (
    <Table>
      <Row label="진료기간">
        {formatKoreanDate(doc.treatmentPeriodFrom)}
        {doc.treatmentPeriodTo && doc.treatmentPeriodTo !== doc.treatmentPeriodFrom
          ? ` ~ ${formatKoreanDate(doc.treatmentPeriodTo)}`
          : ""}
      </Row>
      <Row label="진료과목">{doc.department ?? "-"}</Row>
      <Row label="용도">{doc.purpose ?? "-"}</Row>
      {doc.submitTo && <Row label="제출처">{doc.submitTo}</Row>}
      {doc.remarks && (
        <Row label="비고" wide>
          <MultilineText value={doc.remarks} />
        </Row>
      )}
      <Row label="확인 내용" wide>
        <div className="min-h-[16mm] whitespace-pre-wrap leading-relaxed">
          상기 환자는 위 기간 동안 본원에서 진료받았음을 확인합니다.
        </div>
      </Row>
    </Table>
  );
}

/** 소견서 — 비법정서식. 본문 서술이 전부라 소견 칸을 크게 잡는다. */
function MedicalOpinionBody({ doc }: { doc: CertificateDocument }) {
  return (
    <Table>
      <Row label="병명" wide>
        <DiseaseList diseases={doc.diseases} />
      </Row>
      <Row label="진료일">{formatKoreanDate(doc.visitDate)}</Row>
      <Row label="소견" wide>
        <MultilineText value={doc.opinion} minHeight="46mm" />
      </Row>
      <Row label="용도">{doc.purpose ?? "-"}</Row>
      {doc.submitTo && <Row label="제출처">{doc.submitTo}</Row>}
    </Table>
  );
}

/** 진료의뢰서 — 국민건강보험 요양급여의 기준에 관한 규칙 별지 제4호서식. */
function ReferralBody({ doc }: { doc: CertificateDocument }) {
  return (
    <Table>
      <Row label="의뢰 의료기관">{doc.referralTo ?? "-"}</Row>
      <Row label="상병명" wide>
        <DiseaseList diseases={doc.diseases} />
      </Row>
      <Row label="진료일">{formatKoreanDate(doc.visitDate)}</Row>
      <Row label="의뢰 사유" wide>
        <MultilineText value={doc.referralReason} minHeight="24mm" />
      </Row>
      <Row label="임상 소견 및 경과" wide>
        <MultilineText value={doc.opinion} minHeight="30mm" />
      </Row>
      {doc.remarks && (
        <Row label="비고" wide>
          <MultilineText value={doc.remarks} />
        </Row>
      )}
    </Table>
  );
}

/* ------------------------------------------------------------------ */
/* 공통 조각                                                            */
/* ------------------------------------------------------------------ */

function PatientTable({ doc, type }: { doc: CertificateDocument; type: string }) {
  return (
    <table className="w-full border-collapse border border-black text-[12px]">
      <tbody>
        <tr>
          <Th>성명</Th>
          <Td>{doc.patientName ?? "-"}</Td>
          <Th>성별</Th>
          <Td>{doc.patientGender ?? "-"}</Td>
        </tr>
        <tr>
          <Th>주민등록번호</Th>
          <Td>
            {doc.patientResidentNo ?? "-"}
            <span className="ml-2 text-[10px] text-gray-600">(생년월일 기준 표기)</span>
          </Td>
          <Th>생년월일</Th>
          <Td>{formatKoreanDate(doc.patientBirthDate)}</Td>
        </tr>
        {type !== "PRESCRIPTION" && (
          <tr>
            <Th>연락처</Th>
            <Td colSpan={3}>{doc.patientPhone ?? "-"}</Td>
          </tr>
        )}
      </tbody>
    </table>
  );
}

function Footer({ doc, type }: { doc: CertificateDocument; type: string }) {
  return (
    <footer className="mt-6 text-[12px]">
      <p className="text-center leading-relaxed">{closingSentence(type)}</p>

      <p className="mt-4 text-center text-[13px]">{formatKoreanDate(doc.issuedDate)}</p>

      <div className="mt-5 flex items-end justify-between">
        <div className="leading-relaxed">
          <div className="font-bold">{doc.hospitalName ?? "-"}</div>
          {doc.hospitalAddress && <div>{doc.hospitalAddress}</div>}
          <div className="flex gap-3">
            {doc.hospitalPhone && <span>TEL. {doc.hospitalPhone}</span>}
            {doc.hospitalRegistrationNo && <span>요양기관번호 {doc.hospitalRegistrationNo}</span>}
          </div>
        </div>

        <div className="text-right leading-relaxed">
          {doc.department && <div>{doc.department}</div>}
          <div>면허번호 제 {doc.doctorLicenseNo ?? "-"} 호</div>
          <div className="mt-1 flex items-center justify-end gap-2 text-[14px]">
            <span>
              의사 <span className="font-bold">{doc.doctorName ?? "-"}</span>
            </span>
            {doc.hospitalSealImageUrl ? (
              // 배경 인쇄를 꺼도 도장이 나와야 하므로 CSS 배경이 아니라 img 로 넣는다.
              <img
                src={doc.hospitalSealImageUrl}
                alt="직인"
                className="h-12 w-12 object-contain"
              />
            ) : (
              <span className="text-[11px] text-gray-600">(직인생략)</span>
            )}
          </div>
        </div>
      </div>

      {(doc.formCode || doc.legalBasis) && (
        <div className="mt-6 border-t border-gray-400 pt-1 text-[10px] text-gray-600">
          {[doc.formCode, doc.legalBasis].filter(Boolean).join(" · ")}
        </div>
      )}
    </footer>
  );
}

/** 서류 종류에 따라 맺음말이 다르다. 진단서는 "진단함", 확인서는 "확인함"이다. */
function closingSentence(type: string): string {
  switch (type) {
    case "DIAGNOSIS":
      return "위와 같이 진단합니다.";
    case "MEDICAL_OPINION":
      return "위와 같이 소견을 제출합니다.";
    case "TREATMENT_CONFIRMATION":
      return "위와 같이 진료 사실을 확인합니다.";
    case "REFERRAL":
      return "위 환자의 진료를 의뢰합니다.";
    case "PRESCRIPTION":
      return "위와 같이 처방합니다.";
    default:
      return "";
  }
}

function DiseaseList({ diseases }: { diseases: DiseaseLine[] | null }) {
  if (!diseases || diseases.length === 0) return <span>-</span>;
  return (
    <div className="flex flex-col gap-0.5">
      {diseases.map((d, i) => (
        <div key={`${d.code}-${i}`}>
          {d.name} <span className="text-[11px] text-gray-700">({d.code})</span>
          {d.primary && <span className="ml-1 text-[10px] text-gray-600">[주상병]</span>}
        </div>
      ))}
    </div>
  );
}

/** 서술 칸. 값이 비어 있어도 칸 높이를 유지해야 서식이 무너지지 않는다. */
function MultilineText({ value, minHeight = "12mm" }: { value: string | null; minHeight?: string }) {
  return (
    <div className="whitespace-pre-wrap leading-relaxed" style={{ minHeight }}>
      {value ?? ""}
    </div>
  );
}

function Table({ children, className = "" }: { children: React.ReactNode; className?: string }) {
  return (
    <table className={`w-full border-collapse border border-black text-[12px] ${className}`}>
      <tbody>{children}</tbody>
    </table>
  );
}

function Row({
  label,
  children,
  wide = false,
}: {
  label: string;
  children: React.ReactNode;
  wide?: boolean;
}) {
  return (
    <tr>
      <Th className={wide ? "align-top" : ""}>{label}</Th>
      <Td colSpan={3} className={wide ? "align-top" : ""}>
        {children}
      </Td>
    </tr>
  );
}

function Th({ children, className = "" }: { children: React.ReactNode; className?: string }) {
  return (
    <th
      className={`w-[26%] border border-black bg-gray-100 px-2 py-1.5 text-left font-normal ${className}`}
    >
      {children}
    </th>
  );
}

function Td({
  children,
  colSpan,
  className = "",
}: {
  children: React.ReactNode;
  colSpan?: number;
  className?: string;
}) {
  return (
    <td colSpan={colSpan} className={`border border-black px-2 py-1.5 ${className}`}>
      {children}
    </td>
  );
}

/** 문서에 찍히는 날짜 표기. 스냅샷은 ISO(yyyy-MM-dd)로 굳혀두고 표현만 여기서 바꾼다. */
function formatKoreanDate(iso: string | null): string {
  if (!iso) return "-";
  const [year, month, day] = iso.split("-");
  if (!year || !month || !day) return iso;
  return `${year}년 ${Number(month)}월 ${Number(day)}일`;
}
