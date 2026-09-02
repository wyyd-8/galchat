export function prioritizeNonBaseSkills<T>(
  skills: readonly T[],
  currentValue: (skill: T) => number,
  baseValue: (skill: T) => number,
): T[] {
  return skills
    .map((skill, index) => ({ skill, index, adjusted: currentValue(skill) !== baseValue(skill) }))
    .sort((left, right) => Number(right.adjusted) - Number(left.adjusted) || left.index - right.index)
    .map(({ skill }) => skill)
}
