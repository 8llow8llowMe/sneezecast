import next from '@next/eslint-plugin-next'
import prettier from 'eslint-config-prettier'
import jsxA11y from 'eslint-plugin-jsx-a11y'
import simpleImportSort from 'eslint-plugin-simple-import-sort'
import tseslint from 'typescript-eslint'

/**
 * docs/conventions.md · docs/design-guide.md 의 규칙 중 기계로 강제할 수 있는 것을 못박는다.
 *
 * 규칙을 끌 때는 이 파일에서 전역으로 끄지 말고, 해당 줄에 eslint-disable + 근거 주석을 남긴다.
 */

/** 토큰 밖의 arbitrary value 차단 (design-guide.md "디자인 규칙") */
const noArbitraryValue = {
  selector: 'Literal[value=/\\[[0-9.]+(px|rem|em|%)\\]|\\[#[0-9a-fA-F]{3,8}\\]/]',
  message:
    '토큰 밖의 arbitrary value 금지. 시안에 있는 값이면 tokens.json → tokens.css 에 먼저 올린다',
}

/**
 * 함수가 들어간 arbitrary value 차단.
 *
 * `bg-[linear-gradient(...)_0/1px_100%_no-repeat]` 같은 선언은 Tailwind 가 `/` 를 투명도 수식자로
 * 읽어 **클래스를 조용히 만들지 않는다.** 복합 선언은 app/globals.css 에 이름 있는 클래스로 둔다.
 */
const noComplexArbitrary = {
  selector: 'Literal[value=/\\[[^\\]]*\\([^\\]]*\\)[^\\]]*\\]/]',
  message: '함수가 들어간 arbitrary value 금지. globals.css 에 이름 있는 클래스로 둔다',
}

/** 하드코딩 색상값 차단 — 토큰만 쓴다 */
const noRawHex = {
  selector: 'Literal[value=/^#[0-9a-fA-F]{6}$/]',
  message: 'raw 색상값 금지. 토큰(Tailwind 테마 · CSS 변수)을 쓴다',
}

/** 화면 코드에서 fetch 직접 호출 차단 — API 호출은 src/lib/api/ 로 모은다 */
const noDirectFetch = {
  selector: 'CallExpression[callee.name="fetch"]',
  message: 'fetch 직접 호출 금지. src/lib/api/ 를 거친다 (docs/conventions.md)',
}

export default tseslint.config(
  {
    ignores: ['.next/**', 'coverage/**', 'node_modules/**', 'next-env.d.ts', 'docs/**'],
  },

  // ── 1. 기본
  ...tseslint.configs.recommendedTypeChecked,
  {
    languageOptions: {
      parserOptions: {
        // 루트 설정 파일들은 tsconfig include 밖이므로 기본 프로젝트로 허용한다
        projectService: { allowDefaultProject: ['*.mjs', '*.mts'] },
        tsconfigRootDir: import.meta.dirname,
      },
    },
  },
  jsxA11y.flatConfigs.recommended,
  {
    plugins: { '@next/next': next },
    rules: {
      ...next.configs.recommended.rules,
      ...next.configs['core-web-vitals'].rules,
    },
  },

  // ── 2. 전역 규칙
  {
    plugins: { 'simple-import-sort': simpleImportSort },
    rules: {
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/consistent-type-imports': 'error',

      // docs/conventions.md 의 import 5그룹
      'simple-import-sort/imports': [
        'error',
        {
          groups: [
            ['^react$', '^react-dom', '^next$', '^next/'],
            ['^node:'],
            ['^@?\\w'],
            ['^@/'],
            ['^\\.'],
          ],
        },
      ],
      'simple-import-sort/exports': 'error',

      // 건강·증상 정보는 민감정보다(루트 CLAUDE.md "개인정보"). 보고 내용·기기 토큰을
      // 브라우저 storage 에 아무렇게나 두지 않는다. 꼭 필요하면 저장 위치를 한 모듈로 모으고
      // 그 줄에 eslint-disable + 근거 주석을 남긴다.
      'no-restricted-globals': [
        'error',
        {
          name: 'localStorage',
          message:
            '민감정보를 브라우저 storage 에 두지 않는다. 저장이 필요하면 한 모듈로 모으고 eslint-disable + 근거 주석',
        },
        { name: 'sessionStorage', message: '위와 동일' },
      ],

      'no-restricted-syntax': ['error', noArbitraryValue, noComplexArbitrary, noRawHex],

      // icon-only 버튼의 라벨. 래퍼 컴포넌트에서 오탐이 있어 warn 으로 둔다
      'jsx-a11y/control-has-associated-label': 'warn',

      'no-console': ['error', { allow: ['warn', 'error'] }],
      eqeqeq: ['error', 'always', { null: 'ignore' }],
    },
  },

  // ── 3. 계층 역참조 차단 (docs/conventions.md "디렉터리 구조")
  {
    files: ['src/lib/**', 'src/components/**', 'src/types/**'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['@/features/*', '**/features/*'],
              message: 'lib / components / types → features 역참조 금지 (docs/conventions.md)',
            },
          ],
        },
      ],
    },
  },

  // ── 4. fetch 직접 호출 차단 (src/lib/api 는 예외)
  {
    files: ['src/features/**', 'src/components/**', 'app/**'],
    rules: {
      'no-restricted-syntax': [
        'error',
        noArbitraryValue,
        noComplexArbitrary,
        noRawHex,
        noDirectFetch,
      ],
    },
  },

  // ── 5. 토큰 정의부는 색 하드코딩 예외
  {
    files: ['src/styles/**'],
    rules: { 'no-restricted-syntax': ['error', noDirectFetch] },
  },

  // ── 6. 테스트 파일 완화
  {
    files: ['**/*.test.ts', '**/*.test.tsx', 'src/test/**'],
    rules: {
      'no-restricted-syntax': 'off',
      '@typescript-eslint/no-unsafe-assignment': 'off',
    },
  },

  // ── 7. 설정 파일(.mjs/.mts)은 타입 인식 규칙 대상이 아니다.
  //     플러그인 일부가 타입 선언을 제공하지 않아 no-unsafe-* 가 오탐한다.
  {
    files: ['**/*.mjs', '**/*.mts'],
    extends: [tseslint.configs.disableTypeChecked],
  },

  // ── 8. Prettier 충돌 해소 — 반드시 마지막
  prettier,
)
