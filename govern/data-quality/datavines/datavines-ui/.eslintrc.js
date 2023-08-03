module.exports = {
    env: {
        browser: true,
        node: true,
        es2021: true,
    },
    extends: [
        'airbnb',
        'plugin:import/typescript',
    ],
    parser: '@typescript-eslint/parser',
    parserOptions: {
        ecmaFeatures: {
            jsx: true,
        },
        ecmaVersion: 12,
        sourceType: 'module',
    },
    plugins: [
        'react',
        '@typescript-eslint',
    ],
    rules: {
        indent: ['error', 4, {
            SwitchCase: 1,
            MemberExpression: 1,
            flatTernaryExpressions: false,
        }],
        quotes: ['error', 'single'],
        'no-console': 0,
        'no-use-before-define': 0,
        'import/no-unresolved': 0,
        'no-redeclare': 0,
        'import/no-extraneous-dependencies': 0,
        'max-len': ['error', { code: 200 }],
        'react/jsx-props-no-spreading': 0,
        'react/jsx-filename-extension': [2, { extensions: ['.ts', '.tsx', '.js', '.jsx', '.css'] }],
        'no-unused-vars': 'off',
        '@typescript-eslint/no-unused-vars': ['error'],
        'import/extensions': 0,
        'jsx-a11y/alt-text': 0,
        'jsx-a11y/click-events-have-key-events': 0,
        'jsx-a11y/no-noninteractive-element-interactions': 'off',
        'react/destructuring-assignment': [0],
        'react/prop-types': [0],
        'no-undef': 0,
        'react/jsx-indent': [1, 4, { indentLogicalExpressions: true }],
        'react/jsx-indent-props': [1, 4],
        'jsx-a11y/no-static-element-interactions': 0,
        'no-continue': 0,
        'no-param-reassign': 0,
        'consistent-return': 0,
        'func-names': 0,
        'import/prefer-default-export': 0,
        'react/function-component-definition': 0,
        'react/jsx-no-useless-fragment': 0,
        'react/require-default-props': 0,
        'jsx-a11y/anchor-is-valid': 0,
        'no-empty': 0,
    },
};
