export type SourceType = 'GIT_URL' | 'ARCHIVE';
export type CheckStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED';
export type Severity = 'ERROR' | 'WARNING' | 'INFO';

export interface CustomCriterion {
  name: string;
  scale: string;
  description: string;
}

export interface RepositorySource {
  type: SourceType;
  value: string;
}

export interface CheckResponse {
  id: number;
  status: CheckStatus;
  sourceType: SourceType;
  sourceValue: string;
}

export interface Finding {
  file?: string | null;
  line?: number | null;
  message: string;
  severity: Severity;
}

export interface CriterionResult {
  criterionId: string;
  criterionName: string;
  score: number;
  summary: string;
  findings: Finding[];
}

export interface AnalysisReport {
  checkId: number;
  source: RepositorySource;
  overallScore: number;
  criteria: CriterionResult[];
}

export interface CheckSubmission {
  type: SourceType;
  url: string;
  file: File | null;
  customCriteria: CustomCriterion[];
}

// Enable only after the corresponding backend contracts are implemented.
export const CHECK_FEATURES = {
  archiveUpload: false,
  customCriteria: false,
  maxArchiveBytes: 50 * 1024 * 1024,
  archiveExtensions: ['.zip', '.rar', '.tar', '.tar.gz', '.tgz'],
} as const;

export function repositoryUrlError(value: string): string | null {
  if (!value.trim()) return 'Укажите ссылку на репозиторий.';
  try {
    const url = new URL(value.trim());
    const host = url.hostname.replace(/^www\./, '');
    const parts = url.pathname.split('/').filter(Boolean);
    if (url.protocol !== 'https:' || !['github.com', 'gitlab.com'].includes(host)
      || url.username || url.password || url.port || url.search || url.hash) {
      return 'Используйте HTTPS-ссылку на репозиторий GitHub или GitLab.';
    }
    if (parts.length < 2 || (host === 'github.com' && parts.length !== 2)
      || parts.includes('-') || parts.some(part => !/^[\w.-]+$/.test(part))
      || !parts[parts.length - 1]?.replace(/\.git$/, '')) {
      return 'Укажите адрес самого репозитория, например https://github.com/owner/project.';
    }
    return null;
  } catch {
    return 'Проверьте адрес: он должен начинаться с https://.';
  }
}

export function archiveError(file: File): string | null {
  if (!file.size) return 'Файл пуст. Выберите архив с файлами проекта.';
  if (file.size > CHECK_FEATURES.maxArchiveBytes) return 'Размер архива не должен превышать 50 МиБ.';
  if (!CHECK_FEATURES.archiveExtensions.some(ext => file.name.toLowerCase().endsWith(ext))) {
    return 'Выберите архив ZIP, RAR, TAR, TAR.GZ или TGZ.';
  }
  return null;
}

export function parseCheckId(value: string | null): number | null {
  if (!value || !/^[1-9]\d*$/.test(value)) return null;
  const id = Number(value);
  return Number.isSafeInteger(id) ? id : null;
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function validSource(value: unknown): value is RepositorySource {
  return record(value) && ['GIT_URL', 'ARCHIVE'].includes(String(value['type']))
    && typeof value['value'] === 'string';
}

function validScore(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 100;
}

function validFinding(value: unknown): value is Finding {
  return record(value) && typeof value['message'] === 'string'
    && ['ERROR', 'WARNING', 'INFO'].includes(String(value['severity']))
    && (value['file'] == null || typeof value['file'] === 'string')
    && (value['line'] == null || (typeof value['line'] === 'number'
      && Number.isInteger(value['line']) && value['line'] > 0));
}

function validCriterion(value: unknown): value is CriterionResult {
  return record(value) && typeof value['criterionId'] === 'string'
    && typeof value['criterionName'] === 'string' && validScore(value['score'])
    && typeof value['summary'] === 'string' && Array.isArray(value['findings'])
    && value['findings'].every(validFinding);
}

export function readCheck(value: unknown): CheckResponse {
  if (!record(value) || !Number.isSafeInteger(value['id']) || Number(value['id']) < 1
    || !['PENDING', 'RUNNING', 'DONE', 'FAILED'].includes(String(value['status']))
    || !validSource({ type: value['sourceType'], value: value['sourceValue'] })) {
    throw new Error('Сервис вернул некорректные данные проверки. Попробуйте позже.');
  }
  return value as unknown as CheckResponse;
}

export function readReport(value: unknown): AnalysisReport {
  if (!record(value) || !Number.isSafeInteger(value['checkId']) || Number(value['checkId']) < 1
    || !validSource(value['source']) || !validScore(value['overallScore'])
    || !Array.isArray(value['criteria']) || !value['criteria'].every(validCriterion)) {
    throw new Error('Не удалось прочитать отчёт: сервис вернул некорректные данные.');
  }
  return value as unknown as AnalysisReport;
}
