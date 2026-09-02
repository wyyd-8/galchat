import assert from 'node:assert/strict'
import test from 'node:test'

test('places skills with non-base values first while preserving rule order within each group', async () => {
  const module = await import('./cocModuleCharacterSkills.ts').catch(() => ({})) as {
    prioritizeNonBaseSkills?: <T>(skills: T[], current: (skill: T) => number, base: (skill: T) => number) => T[]
  }
  const skills = [
    { name: '会计', base: 5, value: 5 },
    { name: '潜行', base: 20, value: 45 },
    { name: '人类学', base: 1, value: 1 },
    { name: '侦查', base: 25, value: 60 },
  ]

  const ordered = module.prioritizeNonBaseSkills?.(skills, (skill) => skill.value, (skill) => skill.base)

  assert.deepEqual(ordered?.map((skill) => skill.name), ['潜行', '侦查', '会计', '人类学'])
  assert.deepEqual(skills.map((skill) => skill.name), ['会计', '潜行', '人类学', '侦查'])
})
