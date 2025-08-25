import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class ReviewService {

  private backendUrl = 'http://localhost:8080/review'; // adjust endpoint

  connect(prompt: string): Observable<any> {
    return new Observable(observer => {
      const eventSource = new EventSource(`${this.backendUrl}?prompt=${encodeURIComponent(prompt)}`);

      eventSource.onmessage = (event) => {
        try {
          observer.next(JSON.parse(event.data));
        } catch {
          observer.next(event.data);
        }
      };

      eventSource.onerror = (err) => {
        observer.error(err);
        eventSource.close();
      };

      return () => {
        eventSource.close();
      };
    });
  }
}
