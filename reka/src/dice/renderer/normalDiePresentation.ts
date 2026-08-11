export interface NormalDiePresentation {
  modelKey: string
  faceLabel: string
  displayValue: string
  typeLabelText: string
}

export function resolveNormalDiePresentation(
  sides: number,
  value: number,
): NormalDiePresentation {
  if (sides === 10) {
    return {
      modelKey: 'd10-ones',
      faceLabel: value === 10 ? '0' : String(value),
      displayValue: String(value),
      typeLabelText: 'D10',
    }
  }

  return {
    modelKey: sides === 3 ? 'd6' : `d${sides}`,
    faceLabel: String(value),
    displayValue: String(value),
    typeLabelText: `D${sides}`,
  }
}
