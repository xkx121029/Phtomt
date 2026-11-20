// 精确文本替换的匹配层（参照 opencode 的 edit 工具实现精简移植）。
//
// 为什么需要：模型从 read_file 拿到的是带行号、按 LF 渲染的视图，它照着这段文本
// 拼 old_string 时经常带进三类「看不见的差异」，旧实现一律报 "not found"：
//   1. 文件是 CRLF，模型给的是 LF；
//   2. 模型把智能引号/破折号/不换行空格换成了 ASCII（或反过来）；
//   3. 每行末尾的空格在视图里被压掉了。
// 这三种都不该让一轮工具调用白跑，尤其无人值守时没人能补一句「再试一次」。
//
// 匹配顺序与 opencode 一致：精确 → 标点归一 → 逐行忽略行尾空白。
// 替换时按「LF 视图」定位，再映射回源文件偏移，未改动的区域逐字节保留原行尾，
// 不会因为一次编辑把整个文件从 CRLF 翻成 LF。

const CRLF = '\r\n';

/** 标点/空白归一：只用于比较，不用于回写。 */
const normalizeForMatch = (value) =>
  value
    .replace(/[\u2018\u2019\u201A\u201B]/g, "'")
    .replace(/[\u201C\u201D\u201E\u201F]/g, '"')
    .replace(/[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]/g, '-')
    .replace(/[\u00A0\u2002-\u200A\u202F\u205F\u3000]/g, ' ');

const findOccurrences = (content, search) => {
  const result = [];
  let offset = 0;
  while ((offset = content.indexOf(search, offset)) !== -1) {
    result.push({ start: offset, end: offset + search.length });
    offset += search.length;
  }
  return result;
};

/** 逐行匹配，忽略每行行尾空白；用于「模型少打了几个空格」的场景。 */
const findLineOccurrences = (content, search) => {
  const trailingNewline = search.endsWith('\n');
  const expected = search.split('\n');
  if (trailingNewline) expected.pop();
  const lines = [...content.matchAll(/[^\n]*(?:\n|$)/g)]
    .filter((match) => match[0] !== '')
    .map((match) => {
      const newline = match[0].endsWith('\n');
      const text = newline ? match[0].slice(0, -1) : match[0];
      return {
        start: match.index,
        end: match.index + match[0].length,
        text,
        contentEnd: match.index + text.length - (text.endsWith('\r') ? 1 : 0),
        newline,
      };
    });
  const candidates = lines.flatMap((line, index) => {
    const actual = lines.slice(index, index + expected.length);
    if (actual.length !== expected.length) return [];
    if (
      !actual.every(
        (item, lineIndex) => normalizeForMatch(item.text.trimEnd()) === normalizeForMatch(expected[lineIndex].trimEnd()),
      )
    ) {
      return [];
    }
    const last = actual.at(-1);
    if (trailingNewline && !last.newline) return [];
    return [{ start: line.start, end: trailingNewline ? last.end : last.contentEnd }];
  });
  // 去掉互相重叠的候选（同一段文本被两行同时命中）。
  return candidates.reduce((result, candidate) => {
    if (result.some((match) => match.end > candidate.start && match.start < candidate.end)) return result;
    result.push(candidate);
    return result;
  }, []);
};

/** 文本里占多数的行尾；没有换行时用 fallback。 */
const lineEnding = (text, fallback) => {
  const crlfCount = text.split(CRLF).length - 1;
  const lfCount = text.split('\n').length - 1 - crlfCount;
  if (crlfCount === lfCount) return fallback;
  return crlfCount > lfCount ? CRLF : '\n';
};

/**
 * 把替换文本的换行符对齐到被替换区域原有的行尾：
 * 保留原样的行沿用原行尾，原地改写的行沿用被它替换掉的那一行。
 */
const withRegionEndings = (region, replacement, fallback) => {
  const before = region.split(/\r?\n/);
  const endings = [...region.matchAll(/\r?\n/g)].map((match) => match[0]);
  const after = replacement.split('\n');
  const key = (line) => normalizeForMatch(line.trimEnd());
  const source = alignLines(before.map(key), after.map(key));
  return after
    .map((line, index) => (index === after.length - 1 ? line : line + (endings[source[index]] ?? fallback)))
    .join('');
};

/** 最长公共子序列：返回 after 每行对应的 before 行号。 */
const alignLines = (before, after) => {
  const width = after.length + 1;
  const table = new Uint32Array((before.length + 1) * width);
  for (let i = before.length - 1; i >= 0; i--) {
    for (let j = after.length - 1; j >= 0; j--) {
      table[i * width + j] =
        before[i] === after[j]
          ? table[(i + 1) * width + j + 1] + 1
          : Math.max(table[(i + 1) * width + j], table[i * width + j + 1]);
    }
  }
  const result = [];
  const gap = { before: [], after: [] };
  const flush = () => {
    gap.after.forEach((j, k) => (result[j] = gap.before[k]));
    gap.before = [];
    gap.after = [];
  };
  let i = 0;
  let j = 0;
  while (i < before.length && j < after.length) {
    if (before[i] === after[j]) {
      flush();
      result[j++] = i++;
      continue;
    }
    if (table[(i + 1) * width + j] >= table[i * width + j + 1]) gap.before.push(i++);
    else gap.after.push(j++);
  }
  while (i < before.length) gap.before.push(i++);
  while (j < after.length) gap.after.push(j++);
  flush();
  return result;
};

/** 有序数组中严格小于 offset 的元素个数（二分）。 */
const countBefore = (positions, offset) => {
  let low = 0;
  let high = positions.length;
  while (low < high) {
    const middle = (low + high) >> 1;
    if (positions[middle] < offset) low = middle + 1;
    else high = middle;
  }
  return low;
};

/**
 * 在 LF 视图上定位，再把偏移映射回源文件（CRLF 的 \r 要按位置跳过）。
 * @returns {{matches: Array<{start:number,end:number}>, replace: (m)=>string}}
 */
export const matchView = (source, oldString, newString) => {
  const view = source.replaceAll(CRLF, '\n');
  // 第 i 个 \r 之前的文本在视图里比源文件少 i 个字符。
  const removed = [...source.matchAll(/\r\n/g)].map((match, index) => match.index - index);
  const fileEnding = lineEnding(source, '\n');
  const search = oldString.replaceAll(CRLF, '\n');
  const replacement = newString.replaceAll(CRLF, '\n');

  const exact = findOccurrences(view, search);
  const unicode = exact.length > 0 ? [] : findOccurrences(normalizeForMatch(view), normalizeForMatch(search));
  const trailing = exact.length > 0 || unicode.length > 0 ? [] : findLineOccurrences(view, search);
  const found = exact.length > 0 ? exact : unicode.length > 0 ? unicode : trailing;

  return {
    matches: found.map((match) => ({
      start: match.start + countBefore(removed, match.start),
      end: match.end + countBefore(removed, match.end),
    })),
    replace: (match) => {
      const region = source.slice(match.start, match.end);
      return withRegionEndings(region, replacement, lineEnding(region, fileEnding));
    },
  };
};

const HINT_LINES = 12;
const HINT_LINE_LENGTH = 200;

/**
 * old_string 没命中时给一条可操作的提示，省掉「再读一遍文件」的往返：
 *   - new_string 已在文件里 → 这次编辑大概率已经应用过；
 *   - 否则给最接近的几行（带行号，与 read_file 视图同形）。
 * 只有在调用方本来就有读权限时才应该把结果回给模型（否则等于泄露文件内容）。
 */
export const missHint = (source, oldString, newString) => {
  const view = source.replaceAll(CRLF, '\n');
  const replacement = newString.replaceAll(CRLF, '\n');
  if (replacement.trim().length >= 20 && normalizeForMatch(view).includes(normalizeForMatch(replacement))) {
    return 'new_string is already in the file, so this edit may already be applied.';
  }
  const key = (line) => normalizeForMatch(line).replace(/\s+/g, ' ').trim();
  const distinct = (line) => /[\p{L}\p{N}]/u.test(line);
  const lines = view.split('\n');
  const search = oldString.replaceAll(CRLF, '\n').split('\n');
  const positions = new Map();
  lines.forEach((line, index) => {
    const value = key(line);
    if (!distinct(value)) return;
    const found = positions.get(value);
    if (found) found.push(index);
    else positions.set(value, [index]);
  });
  const votes = new Map();
  const wanted = search.filter((line, offset) => {
    const value = key(line);
    if (!distinct(value)) return false;
    for (const index of positions.get(value) ?? []) votes.set(index - offset, (votes.get(index - offset) ?? 0) + 1);
    return true;
  }).length;
  const [start, count] = [...votes].reduce((best, vote) => (vote[1] > best[1] ? vote : best), [0, 0]);
  if (count === 0 || count * 2 < wanted) return undefined;
  const matched = search.flatMap((line, offset) => {
    const value = key(line);
    const index = start + offset;
    return value !== '' && index >= 0 && index < lines.length && key(lines[index]) === value ? [index] : [];
  });
  if (!matched.length) return undefined;
  const from = matched[0];
  const to = Math.min(matched.at(-1) + 1, from + HINT_LINES);
  return [
    'The closest lines in the file:',
    ...lines
      .slice(from, to)
      .map((line, index) => `${from + index + 1}: ${line.length > HINT_LINE_LENGTH ? `${line.slice(0, HINT_LINE_LENGTH)}…` : line}`),
  ].join('\n');
};

export { normalizeForMatch, findOccurrences, findLineOccurrences, lineEnding };
