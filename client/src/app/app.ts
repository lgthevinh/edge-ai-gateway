import { Component } from '@angular/core';
import { ChatShell } from './ui/chat-shell/chat-shell';

@Component({
  selector: 'app-root',
  imports: [ChatShell],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App {
}
