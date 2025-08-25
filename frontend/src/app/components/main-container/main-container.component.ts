import { Component } from '@angular/core';
import { ReviewService } from 'src/app/services/prompt.service';

@Component({
  selector: 'app-main-container',
  templateUrl: './main-container.component.html',
  styleUrls: ['./main-container.component.scss']
})
export class MainContainerComponent {
  prompt = '';
  mrList: any[] = [];
  showResults = false;
  showReview = false;

  selectedMr: any = null;
  processInfo: any = null;

  constructor(private reviewService: ReviewService) {}

  sendPrompt() {
    if (!this.prompt.trim()) return;

    const dummyResponse = {
      dataType: "mr",
      data: [
        { id: "MR-101", status: "OPEN", author: { name: "Alice" } },
        { id: "MR-102", status: "MERGED", author: { name: "Bob" } },
        { id: "MR-103", status: "CLOSED", author: { name: "Charlie" } }
      ]
    };

    this.handleResponse(dummyResponse);
    this.showResults = true;
  }

  private handleResponse(data: any) {
    if (data.dataType === "mr" && Array.isArray(data.data)) {
      this.mrList = data.data;
    }
  }

  backToPrompt() {
    this.showResults = false;
    this.showReview = false;
    this.mrList = [];
    this.prompt = '';
    this.selectedMr = null;
    this.processInfo = null;
  }

  backToResults() {
    this.showReview = false;
    this.selectedMr = null;
    this.processInfo = null;
  }

  review(mr: any) {
    this.selectedMr = mr;
    this.processInfo = {
      files: 5,
      failed: 2,
      status: 'IN_PROGRESS'
    };
    this.showReview = true;
  }

  getStatusClass(status: string): string {
    switch (status?.toUpperCase()) {
      case 'OPEN': return 'open';
      case 'MERGED': return 'merged';
      case 'CLOSED': return 'closed';
      default: return '';
    }
  }
}
