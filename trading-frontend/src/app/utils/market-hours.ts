export interface MarketStatus {
  isOpen: boolean;
  statusLabel: string;
}

// NSE/BSE cash market: Mon–Fri, 09:15–15:30 IST.
export function isMarketOpen(date: Date = new Date()): boolean {
  const day = date.getDay();
  if (day === 0 || day === 6) return false;
  const minutes = date.getHours() * 60 + date.getMinutes();
  return minutes >= 9 * 60 + 15 && minutes <= 15 * 60 + 30;
}

export function getMarketStatus(date: Date = new Date()): MarketStatus {
  const day = date.getDay();
  if (day === 0 || day === 6) {
    return { isOpen: false, statusLabel: 'Closed · Weekend' };
  }
  if (isMarketOpen(date)) {
    return { isOpen: true, statusLabel: 'Market Open' };
  }
  const minutes = date.getHours() * 60 + date.getMinutes();
  return {
    isOpen: false,
    statusLabel: minutes < 9 * 60 + 15 ? 'Pre-market' : 'Closed',
  };
}
