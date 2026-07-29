import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class TradingService {
  private readonly apiUrl = '/api';

  constructor(private http: HttpClient) {}

  // ─── Portfolio ──────────────────────────────────────────────────────────────
  getPositions(): Observable<any> {
    return this.http.get(`${this.apiUrl}/portfolio/positions`);
  }

  getHoldings(): Observable<any> {
    return this.http.get(`${this.apiUrl}/portfolio/holdings`);
  }

  getFunds(): Observable<any> {
    return this.http.get(`${this.apiUrl}/portfolio/funds`);
  }

  // ─── Orders ─────────────────────────────────────────────────────────────────
  getOrderBook(): Observable<any> {
    return this.http.get(`${this.apiUrl}/orders/book`);
  }

  getTradeBook(): Observable<any> {
    return this.http.get(`${this.apiUrl}/orders/trades`);
  }

  placeOrder(order: {
    variety: string;
    tradingsymbol: string;
    symboltoken: string;
    transactiontype: 'BUY' | 'SELL';
    exchange: string;
    ordertype: 'MARKET' | 'LIMIT' | 'STOPLOSS_LIMIT' | 'STOPLOSS_MARKET';
    producttype: 'INTRADAY' | 'DELIVERY' | 'CARRYFORWARD' | 'MARGIN';
    duration: 'DAY' | 'IOC';
    price: string;
    quantity: string;
    squareoff?: string;
    stoploss?: string;
  }): Observable<any> {
    return this.http.post(`${this.apiUrl}/orders/place`, order);
  }

  cancelOrder(variety: string, orderid: string): Observable<any> {
    return this.http.delete(`${this.apiUrl}/orders/cancel`, { body: { variety, orderid } });
  }

  // ─── Market Data ────────────────────────────────────────────────────────────
  getLTP(exchange: string, symbol: string, token: string): Observable<any> {
    const params = new HttpParams().set('exchange', exchange).set('symbol', symbol).set('token', token);
    return this.http.get(`${this.apiUrl}/market/ltp`, { params });
  }

  getQuote(mode: 'FULL' | 'OHLC' | 'LTP', exchangeTokens: Record<string, string[]>): Observable<any> {
    return this.http.post(`${this.apiUrl}/market/quote`, { mode, exchangeTokens });
  }

  getCandleData(params: {
    exchange: string;
    symboltoken: string;
    interval: string;
    fromdate: string;
    todate: string;
  }): Observable<any> {
    return this.http.post(`${this.apiUrl}/market/candles`, params);
  }

  searchScrip(exchange: string, q: string): Observable<any> {
    const params = new HttpParams().set('exchange', exchange).set('q', q);
    return this.http.get(`${this.apiUrl}/market/search`, { params });
  }

  searchPremium(index: string, from: number, to: number): Observable<any> {
    const params = new HttpParams().set('index', index).set('from', from).set('to', to);
    return this.http.get(`${this.apiUrl}/market/premium-search`, { params });
  }

  getReferenceCandle(exchange: string, token: string, fromTime: string, toTime: string): Observable<any> {
    const params = new HttpParams()
      .set('exchange', exchange)
      .set('token', token)
      .set('fromTime', fromTime)
      .set('toTime', toTime);
    return this.http.get(`${this.apiUrl}/market/reference-candle`, { params });
  }
}
